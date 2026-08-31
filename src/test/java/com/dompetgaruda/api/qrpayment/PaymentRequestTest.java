package com.dompetgaruda.api.qrpayment;

import com.dompetgaruda.api.ApiIntegrationTestBase;
import com.dompetgaruda.api.DeviceIdTestSupport;
import com.dompetgaruda.api.device.dto.CreateUserRequest;
import com.dompetgaruda.api.device.dto.CreateUserResponse;
import com.dompetgaruda.api.device.dto.RegisterDeviceRequest;
import com.dompetgaruda.api.device.dto.RegisterDeviceResponse;
import com.dompetgaruda.api.qrpayment.dto.CreatePaymentRequestRequest;
import com.dompetgaruda.api.qrpayment.dto.CreatePaymentRequestResponse;
import com.dompetgaruda.api.qrpayment.dto.PayPaymentRequestResponse;
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
 * Integration tests for FR20/FR21 — Bayar QR Online.
 * Runs against a real Postgres container (CLAUDE.md §10).
 */
class PaymentRequestTest extends ApiIntegrationTestBase {

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    // -------------------------------------------------------------------------
    // Create — happy path
    // -------------------------------------------------------------------------

    @Test
    void create_happyPath_returns201WithTtlAndUniqueNonce() {
        UUID receiverId = createUser("+62842000001");
        RegisterDeviceResponse receiverDevice = registerDevice(receiverId, "pk-qr-001");

        Instant before = Instant.now();
        CreatePaymentRequestResponse resp = createPaymentRequest(receiverDevice.deviceToken(), 75_000L);
        Instant after = Instant.now();

        assertThat(resp.requestId()).isNotNull();
        assertThat(resp.amount()).isEqualTo(75_000L);
        assertThat(resp.nonce()).isNotBlank();
        assertThat(resp.qrPayload()).isEqualTo(resp.requestId() + "|" + receiverId + "|" + 75_000L + "|" + resp.nonce());
        // Default TTL is 10 minutes (qr-payment.request-ttl-minutes has a YAML default)
        assertThat(resp.expiresAt()).isBetween(before.plus(9, ChronoUnit.MINUTES), after.plus(11, ChronoUnit.MINUTES));
    }

    @Test
    void create_twoRequests_haveDistinctNonces() {
        UUID receiverId = createUser("+62842000002");
        RegisterDeviceResponse receiverDevice = registerDevice(receiverId, "pk-qr-002");

        CreatePaymentRequestResponse first = createPaymentRequest(receiverDevice.deviceToken(), 10_000L);
        CreatePaymentRequestResponse second = createPaymentRequest(receiverDevice.deviceToken(), 10_000L);

        assertThat(first.nonce()).isNotEqualTo(second.nonce());
    }

    @Test
    void create_zeroAmount_returns400() {
        UUID receiverId = createUser("+62842000003");
        RegisterDeviceResponse receiverDevice = registerDevice(receiverId, "pk-qr-003");

        ResponseEntity<String> resp = rest.exchange(
                "/device/payment-request", HttpMethod.POST,
                new HttpEntity<>(new CreatePaymentRequestRequest(0L), deviceHeaders(receiverDevice.deviceToken(), null)),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // -------------------------------------------------------------------------
    // Pay — happy path
    // -------------------------------------------------------------------------

    @Test
    void pay_happyPath_updatesBalancesAndBalancesLedgerAndMarksPaid() {
        UUID receiverId = createUser("+62842000004");
        RegisterDeviceResponse receiverDevice = registerDevice(receiverId, "pk-qr-004");
        UUID payerId = createUser("+62842000005");
        RegisterDeviceResponse payerDevice = registerDevice(payerId, "pk-qr-005");
        topUp(payerId, 200_000L);

        CreatePaymentRequestResponse request = createPaymentRequest(receiverDevice.deviceToken(), 50_000L);
        PayPaymentRequestResponse resp = pay(payerDevice.deviceToken(), request.requestId(), UUID.randomUUID());

        assertThat(resp.transactionId()).isPositive();
        assertThat(resp.payerNewBalance()).isEqualTo(150_000L);

        Long imbalance = jdbc.queryForObject(
                "SELECT SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) " +
                "FROM ledger_entries le JOIN ledger_transactions lt ON lt.transaction_id = le.transaction_id " +
                "WHERE lt.type = 'QR_PAYMENT_ONLINE' AND lt.transaction_id = ?",
                Long.class, resp.transactionId());
        assertThat(imbalance).as("QR_PAYMENT_ONLINE postings must balance").isEqualTo(0L);

        String status = jdbc.queryForObject(
                "SELECT status FROM payment_requests WHERE request_id = ?", String.class, request.requestId());
        assertThat(status).isEqualTo("PAID");
    }

    // -------------------------------------------------------------------------
    // Pay — already PAID (nonce reuse protection)
    // -------------------------------------------------------------------------

    @Test
    void pay_alreadyPaidWithDifferentIdempotencyKey_returns409AndWritesNoAdditionalLedgerEntries() {
        UUID receiverId = createUser("+62842000006");
        RegisterDeviceResponse receiverDevice = registerDevice(receiverId, "pk-qr-006");
        UUID payerId = createUser("+62842000007");
        RegisterDeviceResponse payerDevice = registerDevice(payerId, "pk-qr-007");
        topUp(payerId, 200_000L);

        CreatePaymentRequestResponse request = createPaymentRequest(receiverDevice.deviceToken(), 50_000L);
        pay(payerDevice.deviceToken(), request.requestId(), UUID.randomUUID());

        long entriesAfterFirst = countRows("ledger_entries");

        ResponseEntity<String> second = rest.exchange(
                "/device/payment-request/" + request.requestId() + "/pay", HttpMethod.POST,
                new HttpEntity<>(null, deviceHeaders(payerDevice.deviceToken(), UUID.randomUUID().toString())),
                String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(countRows("ledger_entries")).isEqualTo(entriesAfterFirst);
    }

    // -------------------------------------------------------------------------
    // Pay — expired at check time
    // -------------------------------------------------------------------------

    @Test
    void pay_expiredRequest_returns410AndMarksExpiredAndWritesNoLedgerEntries() {
        UUID receiverId = createUser("+62842000008");
        UUID payerId = createUser("+62842000009");
        RegisterDeviceResponse payerDevice = registerDevice(payerId, "pk-qr-008");
        topUp(payerId, 200_000L);

        UUID requestId = insertExpiredPendingRequest(receiverId, 20_000L);
        long entriesBefore = countRows("ledger_entries");

        ResponseEntity<String> resp = rest.exchange(
                "/device/payment-request/" + requestId + "/pay", HttpMethod.POST,
                new HttpEntity<>(null, deviceHeaders(payerDevice.deviceToken(), UUID.randomUUID().toString())),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(countRows("ledger_entries")).isEqualTo(entriesBefore);

        String status = jdbc.queryForObject(
                "SELECT status FROM payment_requests WHERE request_id = ?", String.class, requestId);
        assertThat(status).isEqualTo("EXPIRED");
    }

    // -------------------------------------------------------------------------
    // Pay — self-payment
    // -------------------------------------------------------------------------

    @Test
    void pay_ownRequest_returns400() {
        UUID receiverId = createUser("+62842000010");
        RegisterDeviceResponse receiverDevice = registerDevice(receiverId, "pk-qr-009");
        topUp(receiverId, 200_000L);

        CreatePaymentRequestResponse request = createPaymentRequest(receiverDevice.deviceToken(), 20_000L);

        ResponseEntity<String> resp = rest.exchange(
                "/device/payment-request/" + request.requestId() + "/pay", HttpMethod.POST,
                new HttpEntity<>(null, deviceHeaders(receiverDevice.deviceToken(), UUID.randomUUID().toString())),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // -------------------------------------------------------------------------
    // Pay — insufficient balance
    // -------------------------------------------------------------------------

    @Test
    void pay_insufficientBalance_returns422AndWritesNoLedgerEntries() {
        UUID receiverId = createUser("+62842000011");
        RegisterDeviceResponse receiverDevice = registerDevice(receiverId, "pk-qr-010");
        UUID payerId = createUser("+62842000012");
        RegisterDeviceResponse payerDevice = registerDevice(payerId, "pk-qr-011");
        topUp(payerId, 10_000L);

        CreatePaymentRequestResponse request = createPaymentRequest(receiverDevice.deviceToken(), 50_000L);
        long entriesBefore = countRows("ledger_entries");

        ResponseEntity<String> resp = rest.exchange(
                "/device/payment-request/" + request.requestId() + "/pay", HttpMethod.POST,
                new HttpEntity<>(null, deviceHeaders(payerDevice.deviceToken(), UUID.randomUUID().toString())),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(countRows("ledger_entries")).isEqualTo(entriesBefore);
    }

    // -------------------------------------------------------------------------
    // Pay — unknown requestId
    // -------------------------------------------------------------------------

    @Test
    void pay_unknownRequestId_returns404() {
        UUID payerId = createUser("+62842000013");
        RegisterDeviceResponse payerDevice = registerDevice(payerId, "pk-qr-012");
        topUp(payerId, 100_000L);

        ResponseEntity<String> resp = rest.exchange(
                "/device/payment-request/" + UUID.randomUUID() + "/pay", HttpMethod.POST,
                new HttpEntity<>(null, deviceHeaders(payerDevice.deviceToken(), UUID.randomUUID().toString())),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // -------------------------------------------------------------------------
    // Idempotency replay — the critical test
    // -------------------------------------------------------------------------

    @Test
    void pay_duplicateIdempotencyKey_replaysIdenticalResponseAndDoesNotDoublePost() {
        UUID receiverId = createUser("+62842000014");
        RegisterDeviceResponse receiverDevice = registerDevice(receiverId, "pk-qr-013");
        UUID payerId = createUser("+62842000015");
        RegisterDeviceResponse payerDevice = registerDevice(payerId, "pk-qr-014");
        topUp(payerId, 200_000L);

        CreatePaymentRequestResponse request = createPaymentRequest(receiverDevice.deviceToken(), 50_000L);
        UUID idempotencyKey = UUID.randomUUID();

        PayPaymentRequestResponse first = pay(payerDevice.deviceToken(), request.requestId(), idempotencyKey);
        long entriesAfterFirst = countRows("ledger_entries");

        PayPaymentRequestResponse second = pay(payerDevice.deviceToken(), request.requestId(), idempotencyKey);

        assertThat(second.transactionId()).isEqualTo(first.transactionId());
        assertThat(second.payerNewBalance()).isEqualTo(first.payerNewBalance());
        assertThat(countRows("ledger_entries"))
                .as("Replay must not write any additional ledger entries")
                .isEqualTo(entriesAfterFirst);
    }

    @Test
    void pay_missingIdempotencyKey_returns400() {
        UUID receiverId = createUser("+62842000016");
        RegisterDeviceResponse receiverDevice = registerDevice(receiverId, "pk-qr-015");
        UUID payerId = createUser("+62842000017");
        RegisterDeviceResponse payerDevice = registerDevice(payerId, "pk-qr-016");
        topUp(payerId, 100_000L);

        CreatePaymentRequestResponse request = createPaymentRequest(receiverDevice.deviceToken(), 20_000L);

        ResponseEntity<String> resp = rest.exchange(
                "/device/payment-request/" + request.requestId() + "/pay", HttpMethod.POST,
                new HttpEntity<>(null, deviceHeaders(payerDevice.deviceToken(), null)),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UUID createUser(String phone) {
        return adminPost("/admin/users", new CreateUserRequest("Test User", phone), CreateUserResponse.class).userId();
    }

    private RegisterDeviceResponse registerDevice(UUID userId, String publicKey) {
        return adminPost("/admin/devices", new RegisterDeviceRequest(userId, DeviceIdTestSupport.randomDeviceId(), publicKey, "Test Device"), RegisterDeviceResponse.class);
    }

    private void topUp(UUID userId, long amount) {
        adminPost("/admin/users/" + userId + "/topup", new TopUpRequest(amount, "test-topup"), TopUpResponse.class);
    }

    private CreatePaymentRequestResponse createPaymentRequest(String deviceToken, long amount) {
        ResponseEntity<CreatePaymentRequestResponse> resp = rest.exchange(
                "/device/payment-request", HttpMethod.POST,
                new HttpEntity<>(new CreatePaymentRequestRequest(amount), deviceHeaders(deviceToken, null)),
                CreatePaymentRequestResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    private PayPaymentRequestResponse pay(String deviceToken, UUID requestId, UUID idempotencyKey) {
        ResponseEntity<PayPaymentRequestResponse> resp = rest.exchange(
                "/device/payment-request/" + requestId + "/pay", HttpMethod.POST,
                new HttpEntity<>(null, deviceHeaders(deviceToken, idempotencyKey.toString())),
                PayPaymentRequestResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    /** Inserts a PENDING request whose expires_at is already in the past, bypassing the TTL config. */
    private UUID insertExpiredPendingRequest(UUID receiverUserId, long amount) {
        UUID requestId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO payment_requests (request_id, receiver_user_id, amount, nonce, expires_at) " +
                "VALUES (?, ?, ?, ?, ?)",
                requestId, receiverUserId, amount, UUID.randomUUID().toString(),
                Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)));
        return requestId;
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
        if (idempotencyKey != null) {
            h.set("Idempotency-Key", idempotencyKey);
        }
        return h;
    }

    private long countRows(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }
}
