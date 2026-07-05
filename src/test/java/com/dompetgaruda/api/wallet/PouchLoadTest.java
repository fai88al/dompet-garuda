package com.dompetgaruda.api.wallet;

import com.dompetgaruda.api.device.dto.CreateUserRequest;
import com.dompetgaruda.api.device.dto.CreateUserResponse;
import com.dompetgaruda.api.device.dto.RegisterDeviceRequest;
import com.dompetgaruda.api.device.dto.RegisterDeviceResponse;
import com.dompetgaruda.api.wallet.dto.PouchLoadRequest;
import com.dompetgaruda.api.wallet.dto.PouchLoadResponse;
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

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for FR3/FR13 — pouch provisioning (POST /device/pouch/load).
 * All tests run against a real Postgres container (CLAUDE.md §10).
 *
 * <p>Cases covered:
 * <ol>
 *   <li>Happy path: 201 returned, certificate row inserted, ledger balanced.</li>
 *   <li>409 when device already has an ACTIVE certificate.</li>
 *   <li>422 when amount exceeds online balance.</li>
 *   <li>400 when amount exceeds configured pouch max.</li>
 *   <li>400 when amount is zero.</li>
 *   <li>400 when amount is negative.</li>
 *   <li>401 with missing device token.</li>
 *   <li>401 with wrong device token.</li>
 *   <li>Atomicity: 409 conflict leaves ledger_entries unchanged.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("api")
@Testcontainers
class PouchLoadTest {

    private static final String ADMIN_TOKEN = "test-admin-token-pouch";
    private static final long   MAX_AMOUNT  = 500_000L;

    // Ed25519 keypair generated once per JVM run; private seed passed to Spring
    // as server.signing-key so the server can sign and the test can verify.
    static final KeyPair TEST_KEY_PAIR;
    static final String  TEST_SIGNING_KEY_BASE64;

    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
            TEST_KEY_PAIR = gen.generateKeyPair();
            // PKCS8-encoded Ed25519 private key: 16-byte header + 32-byte seed = 48 bytes.
            byte[] pkcs8   = TEST_KEY_PAIR.getPrivate().getEncoded();
            byte[] seed    = Arrays.copyOfRange(pkcs8, 16, 48);
            TEST_SIGNING_KEY_BASE64 = Base64.getEncoder().encodeToString(seed);
        } catch (NoSuchAlgorithmException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

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
        registry.add("admin.api-token",         () -> ADMIN_TOKEN);
        registry.add("server.signing-key",      () -> TEST_SIGNING_KEY_BASE64);
        registry.add("pouch.max-amount-idr",    () -> MAX_AMOUNT);
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate     jdbc;

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void pouchLoad_happyPath_returns201WithCertificate() throws Exception {
        UUID userId = createUser("+62831000001");
        RegisterDeviceResponse reg = registerDevice(userId, "pk-pouch-001");
        topUp(userId, 200_000L);

        PouchLoadResponse resp = devicePost(
                "/device/pouch/load",
                new PouchLoadRequest(100_000L),
                reg.deviceToken(),
                PouchLoadResponse.class);

        assertThat(resp.certificateId()).isNotNull();
        assertThat(resp.issuedAmount()).isEqualTo(100_000L);
        assertThat(resp.expiresAt()).isAfter(Instant.now());
        assertThat(resp.serverSignature()).isNotBlank();

        // Verify the server signature covers the certificate fields
        String message = resp.certificateId() + "|" + reg.deviceId() + "|" +
                         resp.issuedAmount() + "|" + resp.expiresAt().getEpochSecond();
        Signature sig = Signature.getInstance("Ed25519");
        sig.initVerify(TEST_KEY_PAIR.getPublic());
        sig.update(message.getBytes(StandardCharsets.UTF_8));
        boolean valid = sig.verify(Base64.getDecoder().decode(resp.serverSignature()));
        assertThat(valid).as("Server signature must be valid Ed25519 over the certificate fields").isTrue();
    }

    @Test
    void pouchLoad_happyPath_insertsActiveCertificateRow() {
        UUID userId = createUser("+62831000002");
        RegisterDeviceResponse reg = registerDevice(userId, "pk-pouch-002");
        topUp(userId, 150_000L);

        PouchLoadResponse resp = devicePost(
                "/device/pouch/load",
                new PouchLoadRequest(50_000L),
                reg.deviceToken(),
                PouchLoadResponse.class);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM offline_certificates WHERE certificate_id = ? AND status = 'ACTIVE'",
                Integer.class,
                resp.certificateId());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void pouchLoad_happyPath_ledgerIsBalanced() {
        UUID userId = createUser("+62831000003");
        RegisterDeviceResponse reg = registerDevice(userId, "pk-pouch-003");
        topUp(userId, 300_000L);

        devicePost("/device/pouch/load",
                new PouchLoadRequest(80_000L),
                reg.deviceToken(),
                PouchLoadResponse.class);

        // For the POUCH_LOAD transaction, SUM(CREDIT) must equal SUM(DEBIT)
        Long imbalance = jdbc.queryForObject(
                "SELECT SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) " +
                "FROM ledger_entries le " +
                "JOIN ledger_transactions lt ON lt.transaction_id = le.transaction_id " +
                "WHERE lt.type = 'POUCH_LOAD'",
                Long.class);
        assertThat(imbalance).as("POUCH_LOAD ledger entries must be balanced (credits - debits = 0)")
                .isEqualTo(0L);
    }

    // -------------------------------------------------------------------------
    // Conflict — existing active certificate
    // -------------------------------------------------------------------------

    @Test
    void pouchLoad_activeCertAlreadyExists_returns409() {
        UUID userId = createUser("+62831000004");
        RegisterDeviceResponse reg = registerDevice(userId, "pk-pouch-004");
        topUp(userId, 400_000L);

        // First load succeeds
        devicePost("/device/pouch/load",
                new PouchLoadRequest(100_000L),
                reg.deviceToken(),
                PouchLoadResponse.class);

        // Second load must be rejected because the active cert still exists
        ResponseEntity<String> conflict = rest.exchange(
                "/device/pouch/load",
                HttpMethod.POST,
                new HttpEntity<>(new PouchLoadRequest(50_000L), deviceHeaders(reg.deviceToken())),
                String.class);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void pouchLoad_activeCertConflict_makesNoLedgerWrite() {
        UUID userId = createUser("+62831000005");
        RegisterDeviceResponse reg = registerDevice(userId, "pk-pouch-005");
        topUp(userId, 400_000L);

        devicePost("/device/pouch/load",
                new PouchLoadRequest(100_000L),
                reg.deviceToken(),
                PouchLoadResponse.class);

        long entriesBefore = countRows("ledger_entries");

        rest.exchange(
                "/device/pouch/load",
                HttpMethod.POST,
                new HttpEntity<>(new PouchLoadRequest(50_000L), deviceHeaders(reg.deviceToken())),
                String.class);

        assertThat(countRows("ledger_entries"))
                .as("Conflict must not write any ledger entries (atomicity)")
                .isEqualTo(entriesBefore);
    }

    // -------------------------------------------------------------------------
    // Insufficient balance
    // -------------------------------------------------------------------------

    @Test
    void pouchLoad_insufficientBalance_returns422() {
        UUID userId = createUser("+62831000006");
        RegisterDeviceResponse reg = registerDevice(userId, "pk-pouch-006");
        topUp(userId, 30_000L);

        ResponseEntity<String> resp = rest.exchange(
                "/device/pouch/load",
                HttpMethod.POST,
                new HttpEntity<>(new PouchLoadRequest(50_000L), deviceHeaders(reg.deviceToken())),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // -------------------------------------------------------------------------
    // Amount validation
    // -------------------------------------------------------------------------

    @Test
    void pouchLoad_amountExceedsMax_returns400() {
        UUID userId = createUser("+62831000007");
        RegisterDeviceResponse reg = registerDevice(userId, "pk-pouch-007");
        topUp(userId, 1_000_000L);

        ResponseEntity<String> resp = rest.exchange(
                "/device/pouch/load",
                HttpMethod.POST,
                new HttpEntity<>(new PouchLoadRequest(MAX_AMOUNT + 1), deviceHeaders(reg.deviceToken())),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void pouchLoad_zeroAmount_returns400() {
        UUID userId = createUser("+62831000008");
        RegisterDeviceResponse reg = registerDevice(userId, "pk-pouch-008");
        topUp(userId, 100_000L);

        ResponseEntity<String> resp = rest.exchange(
                "/device/pouch/load",
                HttpMethod.POST,
                new HttpEntity<>(new PouchLoadRequest(0L), deviceHeaders(reg.deviceToken())),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void pouchLoad_negativeAmount_returns400() {
        UUID userId = createUser("+62831000009");
        RegisterDeviceResponse reg = registerDevice(userId, "pk-pouch-009");
        topUp(userId, 100_000L);

        ResponseEntity<String> resp = rest.exchange(
                "/device/pouch/load",
                HttpMethod.POST,
                new HttpEntity<>(new PouchLoadRequest(-1_000L), deviceHeaders(reg.deviceToken())),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // -------------------------------------------------------------------------
    // Device auth guard
    // -------------------------------------------------------------------------

    @Test
    void pouchLoad_missingToken_returns401() {
        ResponseEntity<String> resp = rest.postForEntity(
                "/device/pouch/load",
                new HttpEntity<>(new PouchLoadRequest(100_000L)),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void pouchLoad_wrongToken_returns401() {
        ResponseEntity<String> resp = rest.exchange(
                "/device/pouch/load",
                HttpMethod.POST,
                new HttpEntity<>(new PouchLoadRequest(100_000L), deviceHeaders("a".repeat(64))),
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

    private <T> T devicePost(String path, Object body, String token, Class<T> responseType) {
        ResponseEntity<T> resp = rest.exchange(
                path,
                HttpMethod.POST,
                new HttpEntity<>(body, deviceHeaders(token)),
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
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return h;
    }

    private long countRows(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }
}
