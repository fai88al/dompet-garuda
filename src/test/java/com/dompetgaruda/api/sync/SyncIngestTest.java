package com.dompetgaruda.api.sync;

import com.dompetgaruda.api.ApiIntegrationTestBase;
import com.dompetgaruda.api.device.dto.CreateUserRequest;
import com.dompetgaruda.api.device.dto.CreateUserResponse;
import com.dompetgaruda.api.device.dto.RegisterDeviceRequest;
import com.dompetgaruda.api.device.dto.RegisterDeviceResponse;
import com.dompetgaruda.api.sync.dto.SyncBatchResponse;
import com.dompetgaruda.api.wallet.dto.PouchLoadRequest;
import com.dompetgaruda.api.wallet.dto.PouchLoadResponse;
import com.dompetgaruda.api.wallet.dto.TopUpRequest;
import com.dompetgaruda.api.wallet.dto.TopUpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for FR5 — sync batch ingest (POST /device/sync).
 *
 * <p>Key invariant under test: §7 rule 5 — the API NEVER writes to ledger_entries or
 * ledger_transactions during sync ingest. Only the worker settles offline transactions.
 *
 * <p>Cases covered:
 * <ol>
 *   <li>Happy path: 202 returned, one PENDING row in sync_inbox, batchId non-null.</li>
 *   <li>Zero ledger writes — the most important invariant for this endpoint.</li>
 *   <li>Late sync: batch uploaded after certificate expiry sets synced_after_expiry = true.</li>
 *   <li>Duplicate upload: same payload twice creates two distinct rows (idempotency is
 *       the worker's responsibility, not the ingest endpoint's).</li>
 *   <li>Malformed JSON body returns 400.</li>
 *   <li>Missing device token returns 401.</li>
 *   <li>Wrong device token returns 401.</li>
 * </ol>
 */
class SyncIngestTest extends ApiIntegrationTestBase {

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate     jdbc;

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void syncIngest_happyPath_returns202WithBatchId() {
        RegisterDeviceResponse reg = setupDeviceWithPouch("+62841000001", "pk-sync-001");

        SyncBatchResponse resp = devicePost(
                "/device/sync",
                minimalBatch(reg),
                reg.deviceToken(),
                SyncBatchResponse.class);

        assertThat(resp.batchId()).isNotNull();
        assertThat(resp.status()).isEqualTo("PENDING");
    }

    @Test
    void syncIngest_happyPath_storesPendingRowInInbox() {
        RegisterDeviceResponse reg = setupDeviceWithPouch("+62841000002", "pk-sync-002");

        SyncBatchResponse resp = devicePost(
                "/device/sync",
                minimalBatch(reg),
                reg.deviceToken(),
                SyncBatchResponse.class);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sync_inbox WHERE batch_id = ? AND status = 'PENDING'",
                Integer.class,
                resp.batchId());
        assertThat(count).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Zero-write invariant — CLAUDE.md §7 rule 5
    // -------------------------------------------------------------------------

    @Test
    void syncIngest_makesZeroWritesToLedger() {
        RegisterDeviceResponse reg = setupDeviceWithPouch("+62841000003", "pk-sync-003");

        long entriesBefore = countRows("ledger_entries");
        long txnsBefore    = countRows("ledger_transactions");

        devicePost("/device/sync", minimalBatch(reg), reg.deviceToken(), SyncBatchResponse.class);

        assertThat(countRows("ledger_entries"))
                .as("ledger_entries must not change during sync ingest (§7 rule 5)")
                .isEqualTo(entriesBefore);
        assertThat(countRows("ledger_transactions"))
                .as("ledger_transactions must not change during sync ingest (§7 rule 5)")
                .isEqualTo(txnsBefore);
    }

    // -------------------------------------------------------------------------
    // Late sync — synced_after_expiry flag
    // -------------------------------------------------------------------------

    @Test
    void syncIngest_lateSyncAfterCertExpiry_setsSyncedAfterExpiryTrue() {
        UUID userId = createUser("+62841000004");
        RegisterDeviceResponse reg = registerDevice(userId, "pk-sync-004");
        topUp(userId, 200_000L);

        // Insert an ALREADY-EXPIRED certificate directly via SQL.
        // (expires_at set 1 hour in the past)
        UUID certId       = UUID.randomUUID();
        UUID pouchAccount = UUID.fromString(jdbc.queryForObject(
                "SELECT account_id FROM accounts WHERE device_id = ? AND type = 'POUCH'",
                String.class, reg.deviceId()));

        jdbc.update(
                "INSERT INTO offline_certificates " +
                "(certificate_id, device_id, pouch_account_id, issued_amount, " +
                " server_signature, status, expires_at) " +
                "VALUES (?, ?, ?, ?, 'test-sig', 'EXPIRED', ?)",
                certId,
                reg.deviceId(),
                pouchAccount,
                50_000L,
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)));

        String batch = batchJson(certId, UUID.randomUUID(), reg.deviceId());

        ResponseEntity<SyncBatchResponse> resp = rest.exchange(
                "/device/sync",
                HttpMethod.POST,
                new HttpEntity<>(batch, deviceJsonHeaders(reg.deviceToken())),
                SyncBatchResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        Boolean afterExpiry = jdbc.queryForObject(
                "SELECT synced_after_expiry FROM sync_inbox WHERE batch_id = ?",
                Boolean.class, resp.getBody().batchId());
        assertThat(afterExpiry).isTrue();
    }

    @Test
    void syncIngest_beforeCertExpiry_setsExpiryFlagFalse() {
        RegisterDeviceResponse reg = setupDeviceWithPouch("+62841000005", "pk-sync-005");

        // setupDeviceWithPouch loads the pouch — creates an ACTIVE cert expiring in 24h
        String activeCertId = jdbc.queryForObject(
                "SELECT certificate_id::text FROM offline_certificates " +
                "WHERE device_id = ? AND status = 'ACTIVE'",
                String.class, reg.deviceId());

        String batch = batchJson(UUID.fromString(activeCertId), UUID.randomUUID(), reg.deviceId());

        ResponseEntity<SyncBatchResponse> resp = rest.exchange(
                "/device/sync",
                HttpMethod.POST,
                new HttpEntity<>(batch, deviceJsonHeaders(reg.deviceToken())),
                SyncBatchResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        Boolean afterExpiry = jdbc.queryForObject(
                "SELECT synced_after_expiry FROM sync_inbox WHERE batch_id = ?",
                Boolean.class, resp.getBody().batchId());
        assertThat(afterExpiry).isFalse();
    }

    // -------------------------------------------------------------------------
    // Duplicate uploads — idempotency is the worker's job
    // -------------------------------------------------------------------------

    @Test
    void syncIngest_duplicateUpload_createsTwoDistinctRows() {
        RegisterDeviceResponse reg = setupDeviceWithPouch("+62841000006", "pk-sync-006");
        String batch = minimalBatch(reg);

        SyncBatchResponse first  = devicePost("/device/sync", batch, reg.deviceToken(), SyncBatchResponse.class);
        SyncBatchResponse second = devicePost("/device/sync", batch, reg.deviceToken(), SyncBatchResponse.class);

        assertThat(first.batchId()).isNotEqualTo(second.batchId());

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sync_inbox WHERE device_id = ? AND status = 'PENDING'",
                Integer.class, reg.deviceId());
        assertThat(count).isGreaterThanOrEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // Malformed body
    // -------------------------------------------------------------------------

    @Test
    void syncIngest_malformedJson_returns400() {
        RegisterDeviceResponse reg = setupDeviceWithPouch("+62841000007", "pk-sync-007");

        HttpHeaders headers = deviceJsonHeaders(reg.deviceToken());
        ResponseEntity<String> resp = rest.exchange(
                "/device/sync",
                HttpMethod.POST,
                new HttpEntity<>("not-valid-json{{{", headers),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void syncIngest_missingCertificateId_returns400() {
        RegisterDeviceResponse reg = setupDeviceWithPouch("+62841000008", "pk-sync-008");

        // certificateId is @NotNull — omitting it must return 400
        String body = """
                {"transactions":[{"offlineTxnId":"%s","receiverDeviceId":"%s",
                "amount":50000,"counter":1,"senderSignature":"sig","receiverSignature":"sig"}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        ResponseEntity<String> resp = rest.exchange(
                "/device/sync",
                HttpMethod.POST,
                new HttpEntity<>(body, deviceJsonHeaders(reg.deviceToken())),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // -------------------------------------------------------------------------
    // Auth guard
    // -------------------------------------------------------------------------

    @Test
    void syncIngest_missingToken_returns401() {
        RegisterDeviceResponse reg = setupDeviceWithPouch("+62841000009", "pk-sync-009");

        ResponseEntity<String> resp = rest.postForEntity(
                "/device/sync",
                new HttpEntity<>(minimalBatch(reg), jsonHeaders()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void syncIngest_wrongToken_returns401() {
        RegisterDeviceResponse reg = setupDeviceWithPouch("+62841000010", "pk-sync-010");

        ResponseEntity<String> resp = rest.exchange(
                "/device/sync",
                HttpMethod.POST,
                new HttpEntity<>(minimalBatch(reg), deviceJsonHeaders("a".repeat(64))),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -------------------------------------------------------------------------
    // Setup helpers
    // -------------------------------------------------------------------------

    /** Creates user + device + top-up + pouch load in one call. Returns device registration. */
    private RegisterDeviceResponse setupDeviceWithPouch(String phone, String publicKey) {
        UUID userId = createUser(phone);
        RegisterDeviceResponse reg = registerDevice(userId, publicKey);
        topUp(userId, 200_000L);
        loadPouch(reg.deviceToken(), 100_000L);
        return reg;
    }

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

    private void loadPouch(String deviceToken, long amount) {
        ResponseEntity<PouchLoadResponse> resp = rest.exchange(
                "/device/pouch/load",
                HttpMethod.POST,
                new HttpEntity<>(new PouchLoadRequest(amount), deviceJsonHeaders(deviceToken)),
                PouchLoadResponse.class);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("Pouch load failed: %s", resp.getStatusCode())
                .isTrue();
    }

    /** Minimal valid batch JSON referencing the device's active certificate. */
    private String minimalBatch(RegisterDeviceResponse reg) {
        String activeCertId = jdbc.queryForObject(
                "SELECT certificate_id::text FROM offline_certificates " +
                "WHERE device_id = ? AND status = 'ACTIVE'",
                String.class, reg.deviceId());
        return batchJson(UUID.fromString(activeCertId), UUID.randomUUID(), reg.deviceId());
    }

    private String batchJson(UUID certId, UUID receiverDeviceId, UUID senderDeviceId) {
        return """
                {
                  "certificateId": "%s",
                  "transactions": [{
                    "offlineTxnId":       "%s",
                    "receiverDeviceId":   "%s",
                    "amount":             50000,
                    "counter":            1,
                    "deviceTimestamp":    "2026-07-07T10:00:00Z",
                    "senderSignature":    "dGVzdC1zaWduYXR1cmU=",
                    "receiverSignature":  "dGVzdC1zaWduYXR1cmU="
                  }]
                }
                """.formatted(certId, UUID.randomUUID(), receiverDeviceId);
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

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

    private <T> T devicePost(String path, String body, String token, Class<T> responseType) {
        ResponseEntity<T> resp = rest.exchange(
                path,
                HttpMethod.POST,
                new HttpEntity<>(body, deviceJsonHeaders(token)),
                responseType);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("Expected 2xx from %s but got %s", path, resp.getStatusCode())
                .isTrue();
        return resp.getBody();
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(testAdminJwt());
        return h;
    }

    private HttpHeaders deviceJsonHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return h;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private long countRows(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }
}
