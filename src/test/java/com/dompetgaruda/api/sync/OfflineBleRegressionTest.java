package com.dompetgaruda.api.sync;

import com.dompetgaruda.api.ApiIntegrationTestBase;
import com.dompetgaruda.api.device.dto.CreateUserRequest;
import com.dompetgaruda.api.device.dto.CreateUserResponse;
import com.dompetgaruda.api.device.dto.RegisterDeviceRequest;
import com.dompetgaruda.api.device.dto.RegisterDeviceResponse;
import com.dompetgaruda.api.sync.dto.SyncOfflineTxnRequest;
import com.dompetgaruda.api.wallet.dto.PouchLoadRequest;
import com.dompetgaruda.api.wallet.dto.PouchLoadResponse;
import com.dompetgaruda.api.wallet.dto.TopUpRequest;
import com.dompetgaruda.api.wallet.dto.TopUpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end regression for FR27/§1a — proves a non-UUID-shaped {@code deviceId}
 * (e.g. a 12-character MAC-derived hex string) behaves IDENTICALLY to the old
 * UUID-shaped id through the full offline BLE flow: admin registration, MQTT-shaped
 * device auth, pouch load / certificate issuance, sync upload, and worker settlement.
 *
 * <p>Runs through the real HTTP surface (api profile) for registration/pouch/sync, then
 * invokes {@link SyncSettlementService#settle} directly — that service is profile-agnostic
 * (see its class javadoc), so it is safely callable from an api-profile test context without
 * waiting on the worker's scheduled poller.
 *
 * <p>PRD FR27 success criterion 13: "A device registered with a non-UUID-shaped string
 * deviceId ... completes the full offline BLE transfer flow ... exactly as a UUID-shaped ID
 * did before."
 */
class OfflineBleRegressionTest extends ApiIntegrationTestBase {

    private static final long TOPUP_AMOUNT  = 200_000L;
    private static final long POUCH_AMOUNT  = 100_000L;
    private static final long TRANSFER_AMOUNT = 40_000L;

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired SyncSettlementService settlementService;
    @Autowired ObjectMapper objectMapper;

    @Test
    void nonUuidDeviceId_completesFullOfflineBleFlow_identicallyToUuidShaped() throws Exception {
        // Deliberately MAC-derived-style, non-UUID-shaped deviceIds (CLAUDE.md §1a).
        String senderDeviceId   = "AABBCCDDEEFF";
        String receiverDeviceId = "112233445566";

        KeyPair senderKeys   = generateEd25519KeyPair();
        KeyPair receiverKeys = generateEd25519KeyPair();

        UUID senderUserId   = createUser("+62890000001");
        UUID receiverUserId = createUser("+62890000002");

        RegisterDeviceResponse sender = registerDevice(senderUserId, senderDeviceId, senderKeys);
        RegisterDeviceResponse receiver = registerDevice(receiverUserId, receiverDeviceId, receiverKeys);

        assertThat(sender.deviceId()).isEqualTo(senderDeviceId);
        assertThat(receiver.deviceId()).isEqualTo(receiverDeviceId);

        topUp(senderUserId, TOPUP_AMOUNT);

        // Load the pouch — issues a signed offline certificate (POUCH_LOAD posting, §3).
        PouchLoadResponse cert = loadPouch(sender.deviceToken(), POUCH_AMOUNT);
        assertThat(cert.certificateId()).isNotNull();

        // Build and sign one offline BLE transfer, exactly per the canonical message format
        // in §14 / SyncSettlementService.buildSigningMessage: "{offlineTxnId}|{senderDeviceId}|
        // {receiverDeviceId}|{amount}|{counter}|{deviceTimestamp}".
        UUID offlineTxnId = UUID.randomUUID();
        SyncOfflineTxnRequest unsigned = new SyncOfflineTxnRequest(
                offlineTxnId, receiverDeviceId, TRANSFER_AMOUNT, 1L, null, null, null, null);
        String message = SyncSettlementService.buildSigningMessage(unsigned, senderDeviceId);

        SyncOfflineTxnRequest txn = new SyncOfflineTxnRequest(
                offlineTxnId, receiverDeviceId, TRANSFER_AMOUNT, 1L, null,
                sign(message, senderKeys), sign(message, receiverKeys), "BLE");

        String batchJson = objectMapper.writeValueAsString(new com.dompetgaruda.api.sync.dto.SyncBatchRequest(
                cert.certificateId(), List.of(txn)));

        ResponseEntity<com.dompetgaruda.api.sync.dto.SyncBatchResponse> syncResp = rest.exchange(
                "/device/sync",
                HttpMethod.POST,
                new HttpEntity<>(batchJson, deviceJsonHeaders(sender.deviceToken())),
                com.dompetgaruda.api.sync.dto.SyncBatchResponse.class);
        assertThat(syncResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID batchId = syncResp.getBody().batchId();
        assertThat(batchId).isNotNull();

        // Drive settlement directly (worker-equivalent) — no ShedLock contention in this test.
        settlementService.settle(batchId);

        assertThat(batchStatus(batchId)).isEqualTo("DONE");
        assertThat(certStatus(cert.certificateId())).isEqualTo("SETTLED");
        assertThat(offlineTxnStatus(offlineTxnId)).isEqualTo("SETTLED");

        // Receiver's ONLINE balance reflects the settled offline transfer.
        long receiverOnlineBalance = onlineBalance(receiverUserId);
        assertThat(receiverOnlineBalance).isEqualTo(TRANSFER_AMOUNT);

        // Sender's remaining pouch value was refunded back to ONLINE at sync.
        long expectedSenderOnline = TOPUP_AMOUNT - POUCH_AMOUNT + (POUCH_AMOUNT - TRANSFER_AMOUNT);
        assertThat(onlineBalance(senderUserId)).isEqualTo(expectedSenderOnline);
    }

    // -------------------------------------------------------------------------
    // Signing helpers
    // -------------------------------------------------------------------------

    private static KeyPair generateEd25519KeyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static String sign(String message, KeyPair kp) throws Exception {
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(kp.getPrivate());
        sig.update(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    private static String base64PublicKey(KeyPair kp) {
        return Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    private UUID createUser(String phone) {
        return adminPost("/admin/users", new CreateUserRequest("Test User", phone), CreateUserResponse.class).userId();
    }

    private RegisterDeviceResponse registerDevice(UUID userId, String deviceId, KeyPair keys) {
        return adminPost("/admin/devices",
                new RegisterDeviceRequest(userId, deviceId, base64PublicKey(keys), "BLE Regression Device"),
                RegisterDeviceResponse.class);
    }

    private void topUp(UUID userId, long amount) {
        adminPost("/admin/users/" + userId + "/topup", new TopUpRequest(amount, "test-topup"), TopUpResponse.class);
    }

    private PouchLoadResponse loadPouch(String deviceToken, long amount) {
        ResponseEntity<PouchLoadResponse> resp = rest.exchange(
                "/device/pouch/load",
                HttpMethod.POST,
                new HttpEntity<>(new PouchLoadRequest(amount), deviceJsonHeaders(deviceToken)),
                PouchLoadResponse.class);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("Pouch load failed: %s", resp.getStatusCode())
                .isTrue();
        return resp.getBody();
    }

    private <T> T adminPost(String path, Object body, Class<T> responseType) {
        ResponseEntity<T> resp = rest.postForEntity(path, new HttpEntity<>(body, adminHeaders()), responseType);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("Expected 2xx from %s but got %s: %s", path, resp.getStatusCode(), resp.getBody())
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

    // -------------------------------------------------------------------------
    // DB assertion helpers
    // -------------------------------------------------------------------------

    private String batchStatus(UUID batchId) {
        return jdbc.queryForObject("SELECT status FROM sync_inbox WHERE batch_id = ?", String.class, batchId);
    }

    private String certStatus(UUID certId) {
        return jdbc.queryForObject("SELECT status FROM offline_certificates WHERE certificate_id = ?", String.class, certId);
    }

    private String offlineTxnStatus(UUID txnId) {
        return jdbc.queryForObject(
                "SELECT settlement_status FROM offline_transactions WHERE offline_txn_id = ?", String.class, txnId);
    }

    private long onlineBalance(UUID userId) {
        Long balance = jdbc.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN le.direction = 'CREDIT' THEN le.amount ELSE -le.amount END), 0) " +
                "FROM ledger_entries le " +
                "JOIN accounts a ON a.account_id = le.account_id " +
                "WHERE a.user_id = ? AND a.type = 'ONLINE'",
                Long.class, userId);
        return balance == null ? 0L : balance;
    }
}
