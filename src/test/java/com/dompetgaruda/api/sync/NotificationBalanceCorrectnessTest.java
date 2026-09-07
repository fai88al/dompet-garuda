package com.dompetgaruda.api.sync;

import com.dompetgaruda.api.ApiIntegrationTestBase;
import com.dompetgaruda.api.DeviceIdTestSupport;
import com.dompetgaruda.api.device.dto.CreateUserRequest;
import com.dompetgaruda.api.device.dto.CreateUserResponse;
import com.dompetgaruda.api.device.dto.RegisterDeviceRequest;
import com.dompetgaruda.api.device.dto.RegisterDeviceResponse;
import com.dompetgaruda.api.mqtt.MosquittoTestSupport;
import com.dompetgaruda.api.sync.dto.SyncOfflineTxnRequest;
import com.dompetgaruda.api.wallet.dto.BalanceResponse;
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
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 Feature A (CLAUDE.md §17 point 6) — THE most important test in this PR:
 * {@code GET /device/balance} is correct even when MQTT is totally unreachable for the
 * entire flow, because balance is derived purely from the ledger (§7 invariant 1) and never
 * references {@code notification_log} (§7 invariant 8).
 *
 * <p>Uses its OWN dedicated, pausable Mosquitto container (mirrors {@code MqttProvisioningTest})
 * rather than {@link ApiIntegrationTestBase}'s always-up shared broker, so it can pause the
 * broker for real without disrupting every other api-profile test class.
 */
@TestPropertySource(properties = "notification.balance-correctness.test.context-marker=true")
class NotificationBalanceCorrectnessTest extends ApiIntegrationTestBase {

    private static final String ADMIN_USERNAME = "dompet-api-admin-test-balance";
    private static final String ADMIN_PASSWORD = "test-balance-admin-pw";

    private static final long TOPUP_AMOUNT    = 200_000L;
    private static final long POUCH_AMOUNT    = 100_000L;
    private static final long TRANSFER_AMOUNT = 40_000L;

    @SuppressWarnings("resource")
    private static final GenericContainer<?> dedicatedMosquitto = startDedicated();

    static {
        mqttContainerOverride = dedicatedMosquitto;
        mqttAdminUsernameOverride = ADMIN_USERNAME;
        mqttAdminPasswordOverride = ADMIN_PASSWORD;
    }

    private static GenericContainer<?> startDedicated() {
        GenericContainer<?> c = MosquittoTestSupport.newContainer(ADMIN_USERNAME, ADMIN_PASSWORD);
        c.start();
        try {
            MosquittoTestSupport.createDeviceRole(MosquittoTestSupport.brokerUrl(c), ADMIN_USERNAME, ADMIN_PASSWORD);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to bootstrap dedicated test Mosquitto broker", e);
        }
        return c;
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired SyncSettlementService settlementService;
    @Autowired ObjectMapper objectMapper;

    @Test
    void balanceCorrect_underTotalMqttFailure() throws Exception {
        // --- Arrange: registration, top-up, pouch load, sync upload — broker is UP for this part
        // (device registration itself needs a reachable MqttAdminClient connection). ---
        KeyPair senderKeys   = generateEd25519KeyPair();
        KeyPair receiverKeys = generateEd25519KeyPair();
        String senderDeviceId   = DeviceIdTestSupport.randomDeviceId();
        String receiverDeviceId = DeviceIdTestSupport.randomDeviceId();

        UUID senderUserId   = createUser("+62895000001");
        UUID receiverUserId = createUser("+62895000002");
        RegisterDeviceResponse sender   = registerDevice(senderUserId, senderDeviceId, senderKeys);
        RegisterDeviceResponse receiver = registerDevice(receiverUserId, receiverDeviceId, receiverKeys);

        topUp(senderUserId, TOPUP_AMOUNT);
        PouchLoadResponse cert = loadPouch(sender.deviceToken(), POUCH_AMOUNT);

        UUID offlineTxnId = UUID.randomUUID();
        SyncOfflineTxnRequest unsigned = new SyncOfflineTxnRequest(
                offlineTxnId, receiverDeviceId, TRANSFER_AMOUNT, 1L, null, null, null, null);
        String message = SyncSettlementService.buildSigningMessage(unsigned, senderDeviceId);
        SyncOfflineTxnRequest txn = new SyncOfflineTxnRequest(
                offlineTxnId, receiverDeviceId, TRANSFER_AMOUNT, 1L, null,
                sign(message, senderKeys), sign(message, receiverKeys), "BLE");

        String batchJson = objectMapper.writeValueAsString(
                new com.dompetgaruda.api.sync.dto.SyncBatchRequest(cert.certificateId(), List.of(txn)));

        ResponseEntity<com.dompetgaruda.api.sync.dto.SyncBatchResponse> syncResp = rest.exchange(
                "/device/sync", HttpMethod.POST,
                new HttpEntity<>(batchJson, deviceIdHeaders(sender.deviceId())),
                com.dompetgaruda.api.sync.dto.SyncBatchResponse.class);
        assertThat(syncResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID batchId = syncResp.getBody().batchId();

        // --- Act: total MQTT outage for the rest of the flow. ---
        pauseBroker();
        try {
            // Settlement itself must never fail or block on a dead broker (§7 invariant 8).
            settlementService.settle(batchId);

            // The one assertion that matters most: balance via the REAL endpoint, unaffected.
            ResponseEntity<BalanceResponse> balanceResp = rest.exchange(
                    "/device/balance", HttpMethod.GET,
                    new HttpEntity<>(bearerHeaders(receiver.deviceToken())),
                    BalanceResponse.class);

            assertThat(balanceResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(balanceResp.getBody()).isNotNull();
            assertThat(balanceResp.getBody().onlineBalance())
                    .as("receiver's online balance must be correct regardless of MQTT/notification state")
                    .isEqualTo(TRANSFER_AMOUNT);

            // Prove the outage was real, not a no-op: the notification never got delivered.
            String notificationStatus = jdbc.queryForObject(
                    "SELECT status FROM notification_log WHERE offline_transaction_id = ?",
                    String.class, offlineTxnId);
            assertThat(notificationStatus)
                    .as("publish must have genuinely failed while the broker was paused")
                    .isEqualTo("PENDING");
        } finally {
            unpauseBroker();
        }

        // Settlement itself completed correctly despite the outage.
        String batchStatus = jdbc.queryForObject(
                "SELECT status FROM sync_inbox WHERE batch_id = ?", String.class, batchId);
        assertThat(batchStatus).isEqualTo("DONE");
    }

    // -------------------------------------------------------------------------
    // Broker outage helpers
    // -------------------------------------------------------------------------

    private static void pauseBroker() {
        DockerClientFactory.instance().client().pauseContainerCmd(dedicatedMosquitto.getContainerId()).exec();
    }

    private static void unpauseBroker() {
        DockerClientFactory.instance().client().unpauseContainerCmd(dedicatedMosquitto.getContainerId()).exec();
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
                new RegisterDeviceRequest(userId, deviceId, base64PublicKey(keys), "Balance Correctness Device"),
                RegisterDeviceResponse.class);
    }

    private void topUp(UUID userId, long amount) {
        adminPost("/admin/users/" + userId + "/topup", new TopUpRequest(amount, "test-topup"), TopUpResponse.class);
    }

    private PouchLoadResponse loadPouch(String deviceToken, long amount) {
        ResponseEntity<PouchLoadResponse> resp = rest.exchange(
                "/device/pouch/load", HttpMethod.POST,
                new HttpEntity<>(new PouchLoadRequest(amount), bearerHeaders(deviceToken)),
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

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return h;
    }

    private HttpHeaders deviceIdHeaders(String deviceId) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Device-Id", deviceId);
        return h;
    }
}
