package com.dompetgaruda.api.transfer;

import com.dompetgaruda.api.ApiIntegrationTestBase;
import com.dompetgaruda.api.DeviceIdTestSupport;
import com.dompetgaruda.api.Ed25519TestSupport;
import com.dompetgaruda.api.device.dto.CreateUserRequest;
import com.dompetgaruda.api.device.dto.CreateUserResponse;
import com.dompetgaruda.api.device.dto.RegisterDeviceRequest;
import com.dompetgaruda.api.device.dto.RegisterDeviceResponse;
import com.dompetgaruda.api.transfer.dto.TransferRequest;
import com.dompetgaruda.api.transfer.dto.TransferResponse;
import com.dompetgaruda.api.wallet.dto.TopUpRequest;
import com.dompetgaruda.api.wallet.dto.TopUpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for FR18/FR19 — {@code POST /device/transfer}.
 * Runs against a real Postgres container (CLAUDE.md §10).
 */
class TransferTest extends ApiIntegrationTestBase {

    // Must match transfer.online.max-amount-idr inherited from ApiIntegrationTestBase
    private static final long MAX_AMOUNT = 10_000_000L;

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void transfer_happyPath_updatesBalancesAndBalancesLedger() {
        UUID senderId = createUser("+62839000001");
        RegisterDeviceResponse senderDevice = registerDevice(senderId, "pk-transfer-001");
        topUp(senderId, 200_000L);

        UUID receiverId = createUser("+62839000002");

        TransferResponse resp = transfer(senderDevice.deviceToken(), receiverId, 50_000L, UUID.randomUUID());

        assertThat(resp.transactionId()).isPositive();
        assertThat(resp.senderNewBalance()).isEqualTo(150_000L);

        Long imbalance = jdbc.queryForObject(
                "SELECT SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) " +
                "FROM ledger_entries le JOIN ledger_transactions lt ON lt.transaction_id = le.transaction_id " +
                "WHERE lt.type = 'ONLINE_TRANSFER' AND lt.transaction_id = ?",
                Long.class, resp.transactionId());
        assertThat(imbalance).as("ONLINE_TRANSFER postings must balance").isEqualTo(0L);
    }

    @Test
    void transfer_happyPath_returns200ResponseShape() {
        UUID senderId = createUser("+62839000003");
        RegisterDeviceResponse senderDevice = registerDevice(senderId, "pk-transfer-002");
        topUp(senderId, 100_000L);
        UUID receiverId = createUser("+62839000004");

        ResponseEntity<TransferResponse> resp = rest.exchange(
                "/device/transfer", HttpMethod.POST,
                new HttpEntity<>(new TransferRequest(receiverId, 10_000L),
                        deviceHeaders(senderDevice.deviceToken(), UUID.randomUUID().toString())),
                TransferResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().transactionId()).isPositive();
        assertThat(resp.getBody().senderNewBalance()).isEqualTo(90_000L);
    }

    // -------------------------------------------------------------------------
    // Self-transfer
    // -------------------------------------------------------------------------

    @Test
    void transfer_toSelf_returns400AndWritesNoLedgerEntries() {
        UUID senderId = createUser("+62839000005");
        RegisterDeviceResponse senderDevice = registerDevice(senderId, "pk-transfer-003");
        topUp(senderId, 100_000L);

        long entriesBefore = countRows("ledger_entries");

        ResponseEntity<String> resp = rest.exchange(
                "/device/transfer", HttpMethod.POST,
                new HttpEntity<>(new TransferRequest(senderId, 10_000L),
                        deviceHeaders(senderDevice.deviceToken(), UUID.randomUUID().toString())),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(countRows("ledger_entries")).isEqualTo(entriesBefore);
    }

    // -------------------------------------------------------------------------
    // Insufficient balance
    // -------------------------------------------------------------------------

    @Test
    void transfer_insufficientBalance_returns422AndWritesNoLedgerEntries() {
        UUID senderId = createUser("+62839000006");
        RegisterDeviceResponse senderDevice = registerDevice(senderId, "pk-transfer-004");
        topUp(senderId, 10_000L);
        UUID receiverId = createUser("+62839000007");

        long entriesBefore = countRows("ledger_entries");

        ResponseEntity<String> resp = rest.exchange(
                "/device/transfer", HttpMethod.POST,
                new HttpEntity<>(new TransferRequest(receiverId, 50_000L),
                        deviceHeaders(senderDevice.deviceToken(), UUID.randomUUID().toString())),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(countRows("ledger_entries")).isEqualTo(entriesBefore);
    }

    // -------------------------------------------------------------------------
    // Unknown receiver
    // -------------------------------------------------------------------------

    @Test
    void transfer_unknownReceiver_returns404() {
        UUID senderId = createUser("+62839000008");
        RegisterDeviceResponse senderDevice = registerDevice(senderId, "pk-transfer-005");
        topUp(senderId, 100_000L);

        ResponseEntity<String> resp = rest.exchange(
                "/device/transfer", HttpMethod.POST,
                new HttpEntity<>(new TransferRequest(UUID.randomUUID(), 10_000L),
                        deviceHeaders(senderDevice.deviceToken(), UUID.randomUUID().toString())),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // -------------------------------------------------------------------------
    // Amount validation
    // -------------------------------------------------------------------------

    @Test
    void transfer_amountExceedsMax_returns400() {
        UUID senderId = createUser("+62839000009");
        RegisterDeviceResponse senderDevice = registerDevice(senderId, "pk-transfer-006");
        topUp(senderId, MAX_AMOUNT + 1_000_000L);
        UUID receiverId = createUser("+62839000010");

        ResponseEntity<String> resp = rest.exchange(
                "/device/transfer", HttpMethod.POST,
                new HttpEntity<>(new TransferRequest(receiverId, MAX_AMOUNT + 1),
                        deviceHeaders(senderDevice.deviceToken(), UUID.randomUUID().toString())),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void transfer_zeroAmount_returns400() {
        UUID senderId = createUser("+62839000011");
        RegisterDeviceResponse senderDevice = registerDevice(senderId, "pk-transfer-007");
        topUp(senderId, 100_000L);
        UUID receiverId = createUser("+62839000012");

        ResponseEntity<String> resp = rest.exchange(
                "/device/transfer", HttpMethod.POST,
                new HttpEntity<>(new TransferRequest(receiverId, 0L),
                        deviceHeaders(senderDevice.deviceToken(), UUID.randomUUID().toString())),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void transfer_negativeAmount_returns400() {
        UUID senderId = createUser("+62839000013");
        RegisterDeviceResponse senderDevice = registerDevice(senderId, "pk-transfer-008");
        topUp(senderId, 100_000L);
        UUID receiverId = createUser("+62839000014");

        ResponseEntity<String> resp = rest.exchange(
                "/device/transfer", HttpMethod.POST,
                new HttpEntity<>(new TransferRequest(receiverId, -1_000L),
                        deviceHeaders(senderDevice.deviceToken(), UUID.randomUUID().toString())),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // -------------------------------------------------------------------------
    // Idempotency-Key header validation
    // -------------------------------------------------------------------------

    @Test
    void transfer_missingIdempotencyKey_returns400() {
        UUID senderId = createUser("+62839000015");
        RegisterDeviceResponse senderDevice = registerDevice(senderId, "pk-transfer-009");
        topUp(senderId, 100_000L);
        UUID receiverId = createUser("+62839000016");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(senderDevice.deviceToken());

        ResponseEntity<String> resp = rest.exchange(
                "/device/transfer", HttpMethod.POST,
                new HttpEntity<>(new TransferRequest(receiverId, 10_000L), headers),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // -------------------------------------------------------------------------
    // Idempotency replay — the critical test
    // -------------------------------------------------------------------------

    @Test
    void transfer_duplicateIdempotencyKey_replaysIdenticalResponseAndDoesNotDoublePost() {
        UUID senderId = createUser("+62839000017");
        RegisterDeviceResponse senderDevice = registerDevice(senderId, "pk-transfer-010");
        topUp(senderId, 200_000L);
        UUID receiverId = createUser("+62839000018");
        UUID idempotencyKey = UUID.randomUUID();

        TransferResponse first = transfer(senderDevice.deviceToken(), receiverId, 50_000L, idempotencyKey);
        long entriesAfterFirst = countRows("ledger_entries");

        TransferResponse second = transfer(senderDevice.deviceToken(), receiverId, 50_000L, idempotencyKey);

        assertThat(second.transactionId()).isEqualTo(first.transactionId());
        assertThat(second.senderNewBalance()).isEqualTo(first.senderNewBalance());
        assertThat(countRows("ledger_entries"))
                .as("Replay must not write any additional ledger entries")
                .isEqualTo(entriesAfterFirst);
        assertThat(countIdempotencyKeysForDevice(senderDevice.deviceId())).isEqualTo(1L);
    }

    @Test
    void transfer_twoDifferentIdempotencyKeys_bothSucceedIndependently() {
        UUID senderId = createUser("+62839000019");
        RegisterDeviceResponse senderDevice = registerDevice(senderId, "pk-transfer-011");
        topUp(senderId, 300_000L);
        UUID receiverId = createUser("+62839000020");

        TransferResponse first = transfer(senderDevice.deviceToken(), receiverId, 50_000L, UUID.randomUUID());
        TransferResponse second = transfer(senderDevice.deviceToken(), receiverId, 50_000L, UUID.randomUUID());

        assertThat(second.transactionId()).isNotEqualTo(first.transactionId());
        assertThat(second.senderNewBalance()).isEqualTo(first.senderNewBalance() - 50_000L);
        assertThat(countIdempotencyKeysForDevice(senderDevice.deviceId())).isEqualTo(2L);
    }

    // -------------------------------------------------------------------------
    // Device auth guard
    // -------------------------------------------------------------------------

    @Test
    void transfer_missingToken_returns401() {
        UUID receiverId = createUser("+62839000021");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());

        ResponseEntity<String> resp = rest.exchange(
                "/device/transfer", HttpMethod.POST,
                new HttpEntity<>(new TransferRequest(receiverId, 10_000L), headers),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void transfer_invalidToken_returns401() {
        UUID receiverId = createUser("+62839000022");

        ResponseEntity<String> resp = rest.exchange(
                "/device/transfer", HttpMethod.POST,
                new HttpEntity<>(new TransferRequest(receiverId, 10_000L),
                        deviceHeaders("a".repeat(64), UUID.randomUUID().toString())),
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
                new RegisterDeviceRequest(userId, DeviceIdTestSupport.randomDeviceId(), Ed25519TestSupport.derivePublicKeyBase64(publicKey), "Test Device"),
                RegisterDeviceResponse.class);
    }

    private void topUp(UUID userId, long amount) {
        adminPost("/admin/users/" + userId + "/topup",
                new TopUpRequest(amount, "test-topup"),
                TopUpResponse.class);
    }

    private TransferResponse transfer(String deviceToken, UUID receiverUserId, long amount, UUID idempotencyKey) {
        ResponseEntity<TransferResponse> resp = rest.exchange(
                "/device/transfer", HttpMethod.POST,
                new HttpEntity<>(new TransferRequest(receiverUserId, amount),
                        deviceHeaders(deviceToken, idempotencyKey.toString())),
                TransferResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private <T> T adminPost(String path, Object body, Class<T> responseType) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(testAdminJwt());
        ResponseEntity<T> resp = rest.postForEntity(path, new HttpEntity<>(body, h), responseType);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("Expected 2xx from %s but got %s: %s", path, resp.getStatusCode(), resp.getBody())
                .isTrue();
        return resp.getBody();
    }

    private HttpHeaders deviceHeaders(String token, String idempotencyKey) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        h.set("Idempotency-Key", idempotencyKey);
        return h;
    }

    private long countRows(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }

    private long countIdempotencyKeysForDevice(String deviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys WHERE device_id = ?", Long.class, deviceId);
        return count == null ? 0L : count;
    }
}
