package com.dompetgaruda.api.admin;

import com.dompetgaruda.api.ApiIntegrationTestBase;
import com.dompetgaruda.api.admin.dto.*;
import com.dompetgaruda.api.device.dto.CreateUserRequest;
import com.dompetgaruda.api.device.dto.CreateUserResponse;
import com.dompetgaruda.api.device.dto.RegisterDeviceRequest;
import com.dompetgaruda.api.device.dto.RegisterDeviceResponse;
import com.dompetgaruda.api.wallet.dto.TopUpRequest;
import com.dompetgaruda.api.wallet.dto.TopUpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for FR10 — admin read endpoints.
 * All tests run against a real Postgres container (CLAUDE.md §10).
 *
 * <p>Cases covered:
 * <ol>
 *   <li>GET /admin/users — two users with different balances appear with correct derived values.</li>
 *   <li>GET /admin/users/{userId} — user appears with devices listed; 404 for unknown id.</li>
 *   <li>GET /admin/devices — device with ACTIVE cert has activeCertificate populated; without cert it is null.</li>
 *   <li>GET /admin/certificates — ?status=ACTIVE filter returns only ACTIVE certs.</li>
 *   <li>GET /admin/sync — rows appear; raw_payload field is NOT present in the response.</li>
 *   <li>GET /admin/flagged — unresolved flag appears; resolved row excluded by default.</li>
 *   <li>Balance derivation — onlineBalance reflects ledger entries after pouch load (not a cached column).</li>
 * </ol>
 */
class AdminDashboardTest extends ApiIntegrationTestBase {

    private static final String ADMIN_TOKEN = "test-admin-token-dashboard";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("admin.api-token", () -> ADMIN_TOKEN);
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate     jdbc;

    // -------------------------------------------------------------------------
    // a. GET /admin/users
    // -------------------------------------------------------------------------

    @Test
    void listUsers_returnsBothUsersWithDerivedBalances() {
        UUID user1 = createUser("+62850000001");
        UUID user2 = createUser("+62850000002");
        topUp(user1, 100_000L);
        topUp(user2, 250_000L);

        List<UserSummaryDto> users = adminGet("/admin/users",
                new ParameterizedTypeReference<>() {});

        UserSummaryDto summary1 = users.stream()
                .filter(u -> u.userId().equals(user1)).findFirst().orElseThrow();
        UserSummaryDto summary2 = users.stream()
                .filter(u -> u.userId().equals(user2)).findFirst().orElseThrow();

        assertThat(summary1.onlineBalance()).isEqualTo(100_000L);
        assertThat(summary2.onlineBalance()).isEqualTo(250_000L);
    }

    @Test
    void listUsers_withoutToken_returns401() {
        ResponseEntity<String> resp = rest.getForEntity("/admin/users", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -------------------------------------------------------------------------
    // b. GET /admin/users/{userId}
    // -------------------------------------------------------------------------

    @Test
    void getUser_returnsUserWithDevices() {
        UUID userId = createUser("+62850000003");
        RegisterDeviceResponse reg = registerDevice(userId, "pk-dashboard-001");

        UserDetailDto detail = adminGet("/admin/users/" + userId,
                new ParameterizedTypeReference<>() {});

        assertThat(detail.userId()).isEqualTo(userId);
        assertThat(detail.devices()).hasSize(1);
        assertThat(detail.devices().get(0).deviceId()).isEqualTo(reg.deviceId());
    }

    @Test
    void getUser_unknownUserId_returns404() {
        ResponseEntity<String> resp = rest.exchange(
                "/admin/users/" + UUID.randomUUID(),
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // -------------------------------------------------------------------------
    // c. GET /admin/devices
    // -------------------------------------------------------------------------

    @Test
    void listDevices_deviceWithActiveCert_hasActiveCertificatePopulated() {
        UUID userId = createUser("+62850000004");
        RegisterDeviceResponse reg = registerDevice(userId, "pk-dashboard-002");

        insertActiveCert(reg.deviceId(), reg.pouchAccountId(), 80_000L);

        List<DeviceWithCertDto> devices = adminGet("/admin/devices",
                new ParameterizedTypeReference<>() {});

        DeviceWithCertDto device = devices.stream()
                .filter(d -> d.deviceId().equals(reg.deviceId())).findFirst().orElseThrow();

        assertThat(device.activeCertificate()).isNotNull();
        assertThat(device.activeCertificate().issuedAmount()).isEqualTo(80_000L);
        assertThat(device.activeCertificate().status()).isEqualTo("ACTIVE");
    }

    @Test
    void listDevices_deviceWithNoCert_hasActiveCertificateNull() {
        UUID userId = createUser("+62850000005");
        RegisterDeviceResponse reg = registerDevice(userId, "pk-dashboard-003");

        List<DeviceWithCertDto> devices = adminGet("/admin/devices",
                new ParameterizedTypeReference<>() {});

        DeviceWithCertDto device = devices.stream()
                .filter(d -> d.deviceId().equals(reg.deviceId())).findFirst().orElseThrow();

        assertThat(device.activeCertificate()).isNull();
    }

    // -------------------------------------------------------------------------
    // d. GET /admin/certificates
    // -------------------------------------------------------------------------

    @Test
    void listCertificates_statusFilter_returnsOnlyMatchingStatus() {
        UUID userId = createUser("+62850000006");
        RegisterDeviceResponse reg = registerDevice(userId, "pk-dashboard-004");

        UUID activeCertId = insertActiveCert(reg.deviceId(), reg.pouchAccountId(), 50_000L);
        insertCertWithStatus(reg.deviceId(), reg.pouchAccountId(), 30_000L, "EXPIRED");

        List<CertificateDto> certs = adminGet("/admin/certificates?status=ACTIVE",
                new ParameterizedTypeReference<>() {});

        assertThat(certs).allMatch(c -> "ACTIVE".equals(c.status()));
        assertThat(certs.stream().map(CertificateDto::certificateId)).contains(activeCertId);
    }

    // -------------------------------------------------------------------------
    // e. GET /admin/sync
    // -------------------------------------------------------------------------

    @Test
    void listSync_rowsAppearAndRawPayloadAbsent() {
        UUID userId = createUser("+62850000007");
        RegisterDeviceResponse reg = registerDevice(userId, "pk-dashboard-005");

        UUID batch1 = insertSyncInboxRow(reg.deviceId());
        UUID batch2 = insertSyncInboxRow(reg.deviceId());

        // Fetch as raw JSON string to verify raw_payload is absent at the wire level
        ResponseEntity<String> rawResp = rest.exchange(
                "/admin/sync",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                String.class);
        assertThat(rawResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rawResp.getBody()).doesNotContain("raw_payload");

        // Also assert structured response contains both batches
        List<SyncBatchDto> batches = adminGet("/admin/sync",
                new ParameterizedTypeReference<>() {});
        List<UUID> batchIds = batches.stream().map(SyncBatchDto::batchId).toList();
        assertThat(batchIds).contains(batch1, batch2);
    }

    // -------------------------------------------------------------------------
    // f. GET /admin/flagged
    // -------------------------------------------------------------------------

    @Test
    void listFlagged_unresolvedAppearsResolvesExcludedByDefault() {
        long unresolvedFlagId = insertFlaggedRow(false);
        long resolvedFlagId   = insertFlaggedRow(true);

        List<FlaggedTransactionDto> flags = adminGet("/admin/flagged",
                new ParameterizedTypeReference<>() {});

        List<Long> flagIds = flags.stream().map(FlaggedTransactionDto::flagId).toList();
        assertThat(flagIds).contains(unresolvedFlagId);
        assertThat(flagIds).doesNotContain(resolvedFlagId);
    }

    // -------------------------------------------------------------------------
    // g. Balance derivation — onlineBalance reflects POUCH_LOAD debit
    // -------------------------------------------------------------------------

    @Test
    void listUsers_balanceDerivation_reflectsLedgerAfterPouchLoad() {
        UUID userId = createUser("+62850000008");
        RegisterDeviceResponse reg = registerDevice(userId, "pk-dashboard-006");

        topUp(userId, 200_000L);

        // Simulate a POUCH_LOAD: debit 50k from ONLINE, credit 50k to POUCH
        UUID onlineAccountId = resolveOnlineAccount(userId);
        jdbc.update(
                "WITH txn AS ( " +
                "  INSERT INTO ledger_transactions (type, reference_type, reference_id, description) " +
                "  VALUES ('POUCH_LOAD', 'TEST', 'balance-derivation-test', 'Test pouch load') " +
                "  RETURNING transaction_id " +
                ") " +
                "INSERT INTO ledger_entries (transaction_id, account_id, direction, amount) " +
                "SELECT txn.transaction_id, ?, 'DEBIT', 50000 FROM txn",
                onlineAccountId);
        UUID pouchAccountId = reg.pouchAccountId();
        jdbc.update(
                "WITH txn AS ( " +
                "  INSERT INTO ledger_transactions (type, reference_type, reference_id, description) " +
                "  VALUES ('POUCH_LOAD', 'TEST', 'balance-derivation-test-credit', 'Test pouch load credit') " +
                "  RETURNING transaction_id " +
                ") " +
                "INSERT INTO ledger_entries (transaction_id, account_id, direction, amount) " +
                "SELECT txn.transaction_id, ?, 'CREDIT', 50000 FROM txn",
                pouchAccountId);

        List<UserSummaryDto> users = adminGet("/admin/users",
                new ParameterizedTypeReference<>() {});

        UserSummaryDto summary = users.stream()
                .filter(u -> u.userId().equals(userId)).findFirst().orElseThrow();

        assertThat(summary.onlineBalance())
                .as("Online balance must be 200000 - 50000 = 150000 (ledger-derived, §7.1)")
                .isEqualTo(150_000L);
    }

    // -------------------------------------------------------------------------
    // Helpers — API calls
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

    private <T> T adminGet(String path, ParameterizedTypeReference<T> type) {
        ResponseEntity<T> resp = rest.exchange(
                path, HttpMethod.GET, new HttpEntity<>(adminHeaders()), type);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(ADMIN_TOKEN);
        return h;
    }

    // -------------------------------------------------------------------------
    // Helpers — direct DB setup
    // -------------------------------------------------------------------------

    private UUID insertActiveCert(UUID deviceId, UUID pouchAccountId, long issuedAmount) {
        return insertCertWithStatus(deviceId, pouchAccountId, issuedAmount, "ACTIVE");
    }

    private UUID insertCertWithStatus(UUID deviceId, UUID pouchAccountId,
                                       long issuedAmount, String status) {
        UUID certId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO offline_certificates " +
                "(certificate_id, device_id, pouch_account_id, issued_amount, " +
                " server_signature, status, expires_at) " +
                "VALUES (?, ?, ?, ?, 'test-sig', ?, ?)",
                certId, deviceId, pouchAccountId, issuedAmount, status,
                Timestamp.from(Instant.now().plus(24, ChronoUnit.HOURS)));
        return certId;
    }

    private UUID insertSyncInboxRow(UUID deviceId) {
        UUID batchId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO sync_inbox (batch_id, device_id, raw_payload, status) " +
                "VALUES (?, ?, CAST(? AS jsonb), 'PENDING')",
                batchId, deviceId, "{\"test\":true}");
        return batchId;
    }

    private long insertFlaggedRow(boolean resolved) {
        return jdbc.queryForObject(
                "INSERT INTO flagged_transactions (reason, detail, resolved) " +
                "VALUES ('MALFORMED', 'dashboard test flag', ?) " +
                "RETURNING flag_id",
                Long.class, resolved);
    }

    private UUID resolveOnlineAccount(UUID userId) {
        return jdbc.queryForObject(
                "SELECT account_id FROM accounts WHERE user_id = ? AND type = 'ONLINE'",
                UUID.class, userId);
    }
}
