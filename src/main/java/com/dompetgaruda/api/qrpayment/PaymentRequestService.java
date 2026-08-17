package com.dompetgaruda.api.qrpayment;

import com.dompetgaruda.api.common.entity.Device;
import com.dompetgaruda.api.ledger.LedgerEntry;
import com.dompetgaruda.api.ledger.LedgerPostingService;
import com.dompetgaruda.api.ledger.PostingRequest;
import com.dompetgaruda.api.qrpayment.dto.CreatePaymentRequestRequest;
import com.dompetgaruda.api.qrpayment.dto.CreatePaymentRequestResponse;
import com.dompetgaruda.api.qrpayment.dto.PayPaymentRequestResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * FR20/FR21 — Bayar QR Online: {@code POST /device/payment-request} (create) and
 * {@code POST /device/payment-request/{requestId}/pay} (settle).
 *
 * <p>{@code pay()} reuses the exact idempotency mechanism from
 * {@code com.dompetgaruda.api.transfer.TransferService} — the same {@code idempotency_keys}
 * table, the same "replay stored response verbatim" behaviour, distinguished only by the
 * {@code endpoint} column value. See that class for the original rationale.
 */
@Service
@Profile("api")
public class PaymentRequestService {

    private static final String ENDPOINT = "device/payment-request/pay";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final LedgerPostingService ledger;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PaymentRequestExpiryWriter expiryWriter;
    private final long ttlMinutes;

    public PaymentRequestService(
            LedgerPostingService ledger,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PaymentRequestExpiryWriter expiryWriter,
            @Value("${qr-payment.request-ttl-minutes}") long ttlMinutes) {
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.expiryWriter = expiryWriter;
        this.ttlMinutes = ttlMinutes;
    }

    @Transactional
    public CreatePaymentRequestResponse create(Device device, CreatePaymentRequestRequest req) {
        Long amount = req.amount();
        if (amount == null || amount <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be > 0");
        }

        UUID requestId = UUID.randomUUID();
        String nonce = generateNonce();
        Instant expiresAt = Instant.now().plus(ttlMinutes, ChronoUnit.MINUTES);

        jdbc.update(
                "INSERT INTO payment_requests (request_id, receiver_user_id, amount, nonce, expires_at) " +
                "VALUES (?, ?, ?, ?, ?)",
                requestId, device.getUserId(), amount, nonce, Timestamp.from(expiresAt));

        String qrPayload = requestId + "|" + device.getUserId() + "|" + amount + "|" + nonce;
        return new CreatePaymentRequestResponse(requestId, amount, nonce, expiresAt, qrPayload);
    }

    @Transactional
    public PayOutcome pay(Device payer, UUID requestId, UUID idempotencyKey) {
        // c) duplicate idempotency key for this device -> replay, skip d-h entirely
        Optional<PayPaymentRequestResponse> replay = findReplay(payer.getDeviceId(), idempotencyKey);
        if (replay.isPresent()) {
            return new PayOutcome(200, replay.get(), null, false);
        }

        // d) requestId exists
        Map<String, Object> row = fetchRequest(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment request not found"));

        // e) status PENDING
        String status = (String) row.get("status");
        if (!"PENDING".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment request is already " + status);
        }

        // f) real-time expiry check — independent commit of EXPIRED, then 410
        Instant expiresAt = ((Timestamp) row.get("expires_at")).toInstant();
        if (!Instant.now().isBefore(expiresAt)) {
            expiryWriter.markExpired(requestId);
            throw new ResponseStatusException(HttpStatus.GONE, "Payment request has expired");
        }

        // g) not a self-payment
        UUID receiverUserId = (UUID) row.get("receiver_user_id");
        if (payer.getUserId().equals(receiverUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot pay your own request");
        }

        // h) sufficient balance, derived fresh from the ledger
        long amount = ((Number) row.get("amount")).longValue();
        UUID payerAccount = ledger.resolveOnlineAccount(payer.getUserId());
        long payerBalance = ledger.getBalance(payerAccount);
        if (payerBalance < amount) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Insufficient online balance: available=" + payerBalance + ", requested=" + amount);
        }

        UUID receiverAccount = ledger.resolveOnlineAccount(receiverUserId);

        long transactionId = ledger.post(new PostingRequest(
                "QR_PAYMENT_ONLINE",
                "PAYMENT_REQUEST",
                requestId.toString(),
                "Bayar QR payment for request " + requestId,
                List.of(
                        new LedgerEntry(payerAccount, "DEBIT", amount),
                        new LedgerEntry(receiverAccount, "CREDIT", amount)
                )
        ));

        jdbc.update(
                "UPDATE payment_requests SET status = 'PAID', paid_at = now(), " +
                "paid_by_user_id = ?, ledger_transaction_id = ? WHERE request_id = ?",
                payer.getUserId(), transactionId, requestId);

        PayPaymentRequestResponse response = new PayPaymentRequestResponse(transactionId, payerBalance - amount);
        storeIdempotencyKey(payer.getDeviceId(), idempotencyKey, transactionId, response);

        UUID receiverDeviceId = findReceiverDeviceId(receiverUserId).orElse(null);
        return new PayOutcome(200, response, receiverDeviceId, true);
    }

    private String generateNonce() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private Optional<Map<String, Object>> fetchRequest(UUID requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, expires_at, receiver_user_id, amount FROM payment_requests WHERE request_id = ?",
                requestId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private Optional<PayPaymentRequestResponse> findReplay(UUID deviceId, UUID idempotencyKey) {
        List<String> bodies = jdbc.query(
                "SELECT response_body::text FROM idempotency_keys WHERE device_id = ? AND idempotency_key = ?",
                (rs, rowNum) -> rs.getString(1),
                deviceId, idempotencyKey);
        if (bodies.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(readResponse(bodies.get(0)));
    }

    private void storeIdempotencyKey(UUID deviceId, UUID idempotencyKey, long transactionId, PayPaymentRequestResponse response) {
        String json = writeResponse(response);
        try {
            jdbc.update(
                    "INSERT INTO idempotency_keys " +
                    "(device_id, idempotency_key, endpoint, transaction_id, response_status, response_body) " +
                    "VALUES (?, ?, ?, ?, 200, ?::jsonb)",
                    deviceId, idempotencyKey, ENDPOINT, transactionId, json);
        } catch (DataIntegrityViolationException e) {
            // Same race-safety note as TransferService.storeIdempotencyKey: the UNIQUE
            // constraint is the final guard; the ledger posting above rolls back with this.
            throw e;
        }
    }

    private Optional<UUID> findReceiverDeviceId(UUID receiverUserId) {
        try {
            UUID deviceId = jdbc.queryForObject(
                    "SELECT device_id FROM devices WHERE user_id = ? AND status = 'ACTIVE' " +
                    "ORDER BY registered_at LIMIT 1",
                    UUID.class,
                    receiverUserId);
            return Optional.ofNullable(deviceId);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private PayPaymentRequestResponse readResponse(String json) {
        try {
            return objectMapper.readValue(json, PayPaymentRequestResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt stored idempotency response", e);
        }
    }

    private String writeResponse(PayPaymentRequestResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize pay response", e);
        }
    }
}
