package com.dompetgaruda.api.transfer;

import com.dompetgaruda.api.common.entity.Device;
import com.dompetgaruda.api.common.repository.DeviceRepository;
import com.dompetgaruda.api.ledger.LedgerEntry;
import com.dompetgaruda.api.ledger.LedgerPostingService;
import com.dompetgaruda.api.ledger.PostingRequest;
import com.dompetgaruda.api.transfer.dto.TransferRequest;
import com.dompetgaruda.api.transfer.dto.TransferResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * FR18/FR19 — {@code POST /device/transfer}: a synchronous, server-mediated transfer
 * between two users' ONLINE ledger accounts.
 *
 * <p>Unlike the offline flow, this posts directly to the ledger inside the same request
 * (CLAUDE.md §3 "Online flows"). Idempotency is enforced by the DB-level UNIQUE constraint
 * on {@code idempotency_keys.idempotency_key} (CLAUDE.md §7 rule 4, §14.4) — the device
 * generates the key, and a duplicate replays the stored response verbatim without
 * reprocessing.
 *
 * <p>Validation order (CLAUDE.md §14.1) — steps a/b (device_id lookup, header format) run in
 * {@link TransferController}; this class runs c through g, all inside one
 * {@code @Transactional} method so a rejection at any step leaves zero rows written.
 */
@Service
@Profile("api")
public class TransferService {

    private static final String ENDPOINT = "device/transfer";

    private final LedgerPostingService ledger;
    private final DeviceRepository deviceRepository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final long maxAmountIdr;

    public TransferService(
            LedgerPostingService ledger,
            DeviceRepository deviceRepository,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            @Value("${transfer.online.max-amount-idr}") long maxAmountIdr) {
        this.ledger = ledger;
        this.deviceRepository = deviceRepository;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.maxAmountIdr = maxAmountIdr;
    }

    @Transactional
    public TransferOutcome transfer(Device device, UUID idempotencyKey, TransferRequest req) {
        // c) duplicate idempotency key for this device -> replay, skip d-g entirely
        Optional<TransferResponse> replay = findReplay(device.getDeviceId(), idempotencyKey);
        if (replay.isPresent()) {
            return new TransferOutcome(200, replay.get(), null, false);
        }

        // d) receiver device exists
        if (req.receiverDeviceId() == null || req.receiverDeviceId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receiver device not found");
        }
        Device receiverDevice = deviceRepository.findById(req.receiverDeviceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Receiver device not found"));
        UUID receiverUserId = receiverDevice.getUserId();

        // e) not a self-transfer (same owning user, regardless of which of their devices sent it)
        if (receiverUserId.equals(device.getUserId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot transfer to yourself");
        }

        // f) amount bounds
        Long amount = req.amount();
        if (amount == null || amount <= 0 || amount > maxAmountIdr) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Amount must be > 0 and <= " + maxAmountIdr + " IDR");
        }

        // g) sufficient balance, derived fresh from the ledger
        UUID senderAccount = ledger.resolveOnlineAccount(device.getUserId());
        long senderBalance = ledger.getBalance(senderAccount);
        if (senderBalance < amount) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Insufficient online balance: available=" + senderBalance + ", requested=" + amount);
        }

        UUID receiverAccount = ledger.resolveOnlineAccount(receiverUserId);

        long transactionId = ledger.post(new PostingRequest(
                "ONLINE_TRANSFER",
                "USER",
                receiverUserId.toString(),
                "Online transfer to " + receiverUserId,
                List.of(
                        new LedgerEntry(senderAccount, "DEBIT", amount),
                        new LedgerEntry(receiverAccount, "CREDIT", amount)
                )
        ));

        TransferResponse response = new TransferResponse(transactionId, senderBalance - amount);
        storeIdempotencyKey(device.getDeviceId(), idempotencyKey, transactionId, response);

        return new TransferOutcome(200, response, req.receiverDeviceId(), true);
    }

    private Optional<TransferResponse> findReplay(String deviceId, UUID idempotencyKey) {
        List<String> bodies = jdbc.query(
                "SELECT response_body::text FROM idempotency_keys WHERE device_id = ? AND idempotency_key = ?",
                (rs, rowNum) -> rs.getString(1),
                deviceId, idempotencyKey);
        if (bodies.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(readResponse(bodies.get(0)));
    }

    private void storeIdempotencyKey(String deviceId, UUID idempotencyKey, long transactionId, TransferResponse response) {
        String json = writeResponse(response);
        try {
            jdbc.update(
                    "INSERT INTO idempotency_keys " +
                    "(device_id, idempotency_key, endpoint, transaction_id, response_status, response_body) " +
                    "VALUES (?, ?, ?, ?, 200, ?::jsonb)",
                    deviceId, idempotencyKey, ENDPOINT, transactionId, json);
        } catch (DataIntegrityViolationException e) {
            // Concurrent duplicate submission raced us past the replay check above; the
            // UNIQUE constraint is the final guard (CLAUDE.md §7 rule 4). Let the caller's
            // transaction roll back — the ledger posting above rolls back with it, so no
            // double-post occurs. The retried request will find the winner's row on its
            // own replay check.
            throw e;
        }
    }

    private TransferResponse readResponse(String json) {
        try {
            return objectMapper.readValue(json, TransferResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt stored idempotency response", e);
        }
    }

    private String writeResponse(TransferResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize transfer response", e);
        }
    }
}
