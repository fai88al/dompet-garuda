package com.dompetgaruda.api.wallet;

import com.dompetgaruda.api.device.dto.CreateUserRequest;
import com.dompetgaruda.api.device.dto.CreateUserResponse;
import com.dompetgaruda.api.device.dto.RegisterDeviceRequest;
import com.dompetgaruda.api.device.dto.RegisterDeviceResponse;
import com.dompetgaruda.api.wallet.dto.BalanceResponse;
import com.dompetgaruda.api.wallet.dto.TopUpRequest;
import com.dompetgaruda.api.wallet.dto.TopUpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for FR14 — balance enquiry (GET /device/balance).
 * All tests run against a real Postgres container (CLAUDE.md §10).
 *
 * <p>Cases covered:
 * <ol>
 *   <li>After a top-up of X, onlineBalance returns X and pouchCommitted returns 0.</li>
 *   <li>With an ACTIVE certificate row, pouchCommitted returns issued_amount.</li>
 *   <li>The endpoint makes zero writes to ledger_entries or ledger_transactions.</li>
 *   <li>Missing Authorization header returns 401.</li>
 *   <li>Wrong/unregistered device token returns 401.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("api")
@Testcontainers
class BalanceEnquiryTest {

    private static final String ADMIN_TOKEN = "test-admin-token-balance";

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("dompet")
            .withUsername("dompet")
            .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("SPRING_DATASOURCE_URL", postgres::getJdbcUrl);
        registry.add("SPRING_DATASOURCE_USERNAME", postgres::getUsername);
        registry.add("SPRING_DATASOURCE_PASSWORD", postgres::getPassword);
        registry.add("admin.api-token",      () -> ADMIN_TOKEN);
        registry.add("server.signing-key",   () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("pouch.max-amount-idr", () -> 500_000L);
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    // -------------------------------------------------------------------------
    // Balance figures
    // -------------------------------------------------------------------------

    @Test
    void balance_afterTopUp_returnsOnlineBalanceAndZeroPouch() {
        UUID userId = createUser("+62821000001");
        String deviceToken = registerDevice(userId, "pub-bal-001").deviceToken();
        topUp(userId, 150_000L);

        BalanceResponse resp = deviceGet("/device/balance", deviceToken, BalanceResponse.class);

        assertThat(resp.onlineBalance()).isEqualTo(150_000L);
        assertThat(resp.pouchCommitted()).isEqualTo(0L);
    }

    @Test
    void balance_withActiveCertificate_returnsPouchCommitted() {
        UUID userId = createUser("+62821000002");
        RegisterDeviceResponse reg = registerDevice(userId, "pub-bal-002");
        topUp(userId, 100_000L);

        // Insert an ACTIVE certificate directly — simulates a pouch load without
        // implementing the full pouch-load endpoint in this PR.
        // Use java.sql.Timestamp for the TIMESTAMPTZ column: the PostgreSQL JDBC driver
        // has no setObject mapping for java.time.Instant and falls through to setString,
        // which sends text OID — PostgreSQL rejects it with 42804 (datatype mismatch).
        jdbc.update(
                "INSERT INTO offline_certificates " +
                "(certificate_id, device_id, pouch_account_id, issued_amount, " +
                " server_signature, status, expires_at) " +
                "VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)",
                UUID.randomUUID(),
                reg.deviceId(),
                reg.pouchAccountId(),
                50_000L,
                "test-signature",
                Timestamp.from(Instant.now().plus(24, ChronoUnit.HOURS)));

        BalanceResponse resp = deviceGet("/device/balance", reg.deviceToken(), BalanceResponse.class);

        assertThat(resp.pouchCommitted()).isEqualTo(50_000L);
        assertThat(resp.onlineBalance()).isEqualTo(100_000L);
    }

    // -------------------------------------------------------------------------
    // Zero-write invariant (CLAUDE.md §7.1 / FR14)
    // -------------------------------------------------------------------------

    @Test
    void balance_makesZeroWritesToLedger() {
        UUID userId = createUser("+62821000003");
        String deviceToken = registerDevice(userId, "pub-bal-003").deviceToken();
        topUp(userId, 75_000L);

        long entriesBefore = countRows("ledger_entries");
        long txnsBefore    = countRows("ledger_transactions");

        deviceGet("/device/balance", deviceToken, BalanceResponse.class);

        assertThat(countRows("ledger_entries")).as("ledger_entries must not change on balance read")
                .isEqualTo(entriesBefore);
        assertThat(countRows("ledger_transactions")).as("ledger_transactions must not change on balance read")
                .isEqualTo(txnsBefore);
    }

    // -------------------------------------------------------------------------
    // Authentication guard
    // -------------------------------------------------------------------------

    @Test
    void balance_missingToken_returns401() {
        ResponseEntity<String> resp = rest.getForEntity("/device/balance", String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void balance_wrongToken_returns401() {
        // A well-formed 64-char hex token that is not registered to any device
        String nonexistentToken = "a".repeat(64);

        ResponseEntity<String> resp = rest.exchange(
                "/device/balance",
                HttpMethod.GET,
                new HttpEntity<>(deviceHeaders(nonexistentToken)),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UUID createUser(String phone) {
        return adminPost("/admin/users",
                new CreateUserRequest("Test User", phone),
                CreateUserResponse.class).userId();
    }

    private RegisterDeviceResponse registerDevice(UUID userId, String publicKey) {
        return adminPost("/admin/devices",
                new RegisterDeviceRequest(userId, publicKey, "Test Device"),
                RegisterDeviceResponse.class);
    }

    private void topUp(UUID userId, long amount) {
        adminPost("/admin/users/" + userId + "/topup",
                new TopUpRequest(amount, "test-topup"),
                TopUpResponse.class);
    }

    private <T> T adminPost(String path, Object body, Class<T> responseType) {
        ResponseEntity<T> resp = rest.postForEntity(
                path,
                new HttpEntity<>(body, adminHeaders()),
                responseType);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("Expected 2xx from %s but got %s: %s", path, resp.getStatusCode(), resp.getBody())
                .isTrue();
        return resp.getBody();
    }

    private <T> T deviceGet(String path, String deviceToken, Class<T> responseType) {
        ResponseEntity<T> resp = rest.exchange(
                path,
                HttpMethod.GET,
                new HttpEntity<>(deviceHeaders(deviceToken)),
                responseType);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("Expected 2xx from %s but got %s", path, resp.getStatusCode())
                .isTrue();
        return resp.getBody();
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(ADMIN_TOKEN);
        return h;
    }

    private HttpHeaders deviceHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private long countRows(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }
}
