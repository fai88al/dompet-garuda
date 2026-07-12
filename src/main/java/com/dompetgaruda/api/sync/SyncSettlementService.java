package com.dompetgaruda.api.sync;

import com.dompetgaruda.api.common.Ed25519Verifier;
import com.dompetgaruda.api.ledger.LedgerEntry;
import com.dompetgaruda.api.ledger.LedgerPostingService;
import com.dompetgaruda.api.ledger.PostingRequest;
import com.dompetgaruda.api.mqtt.MqttPublisherService;
import com.dompetgaruda.api.sync.dto.SyncBatchRequest;
import com.dompetgaruda.api.sync.dto.SyncOfflineTxnRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Settles one PENDING batch from {@code sync_inbox}.
 *
 * <p>Profile-agnostic — callable by the worker poller and directly by integration tests.
 *
 * <h2>Per-transaction flow (§3 + §7)</h2>
 * <ol>
 *   <li>Counter check: {@code counter > devices.last_counter}, else COUNTER_REPLAY flag.</li>
 *   <li>Sender Ed25519 signature check, else BAD_SIGNATURE flag.</li>
 *   <li>Receiver Ed25519 signature check, else BAD_SIGNATURE flag.</li>
 *   <li>Outflow cap check: running outflow ≤ issued_amount, else OVER_LIMIT flag.</li>
 *   <li>Post OFFLINE_TRANSFER + insert offline_transactions + update last_counter (ONE DB tx).</li>
 * </ol>
 *
 * <p>After all transactions: post POUCH_REFUND for unspent, mark cert SETTLED, mark batch DONE
 * (all in one final DB transaction).
 *
 * <p>If JSON parsing fails, batch is marked FAILED with MALFORMED flag and control returns
 * normally so the poller can continue to the next batch (§7 invariant 11).
 *
 * <p>Per §7 rule 9: signatures and public key bytes are never logged.
 */
@Service
public class SyncSettlementService {

    private static final Logger log = LoggerFactory.getLogger(SyncSettlementService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final LedgerPostingService ledger;
    private final Ed25519Verifier verifier;
    private final ObjectMapper objectMapper;
    // Null in the api profile — MqttPublisherService is @Profile("worker") only
    @Autowired(required = false)
    private MqttPublisherService mqttPublisher;

    public SyncSettlementService(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                                  LedgerPostingService ledger, Ed25519Verifier verifier,
                                  ObjectMapper objectMapper) {
        this.jdbc         = jdbc;
        this.tx           = new TransactionTemplate(txManager);
        this.ledger       = ledger;
        this.verifier     = verifier;
        this.objectMapper = objectMapper;
    }

    /**
     * Settles the batch identified by {@code batchId}.
     *
     * <p>The batch must already be in PROCESSING state (set by the poller before calling here).
     * On return, the batch will be DONE or FAILED. Never throws — all failures are handled
     * internally and materialised as DB state.
     */
    public void settle(UUID batchId) {
        // 1. Read the batch row
        Map<String, Object> batchRow;
        try {
            batchRow = jdbc.queryForMap(
                    "SELECT device_id, raw_payload::text AS raw_payload, synced_after_expiry " +
                    "FROM sync_inbox WHERE batch_id = ?", batchId);
        } catch (Exception e) {
            log.error("Could not read batch row {}: {}", batchId, e.getMessage());
            markFailed(batchId, "Failed to read batch row: " + e.getMessage());
            return;
        }

        UUID deviceId = (UUID) batchRow.get("device_id");
        boolean syncedAfterExpiry = Boolean.TRUE.equals(batchRow.get("synced_after_expiry"));
        String rawJson = (String) batchRow.get("raw_payload");

        // 2. Parse JSON payload — malformed JSON is a poison-pill: flag and skip
        SyncBatchRequest batch;
        try {
            batch = objectMapper.readValue(rawJson, SyncBatchRequest.class);
            if (batch.certificateId() == null) {
                throw new IllegalArgumentException("certificateId is null");
            }
        } catch (Exception e) {
            log.warn("Batch {} has malformed payload", batchId);
            markFailed(batchId, "Malformed payload: " + e.getMessage());
            insertFlag(null, batchId, null, "MALFORMED", "JSON parse failed: " + e.getMessage());
            if (mqttPublisher != null) {
                mqttPublisher.publishSyncResult(deviceId.toString(), batchId.toString(), "FAILED", "Malformed payload");
            }
            return;
        }

        // 3. Load the certificate (must belong to this device and not yet settled/revoked)
        List<Map<String, Object>> certs = jdbc.queryForList(
                "SELECT certificate_id, pouch_account_id, issued_amount " +
                "FROM offline_certificates " +
                "WHERE certificate_id = ? AND device_id = ? AND status IN ('ACTIVE', 'EXPIRED')",
                batch.certificateId(), deviceId);

        if (certs.isEmpty()) {
            log.warn("Batch {} references unknown or already-settled certificate {}", batchId, batch.certificateId());
            markFailed(batchId, "No valid certificate found: " + batch.certificateId());
            insertFlag(null, batchId, batch.certificateId(), "MALFORMED",
                    "Certificate not found or already settled/revoked");
            if (mqttPublisher != null) {
                mqttPublisher.publishSyncResult(deviceId.toString(), batchId.toString(), "FAILED", "No valid certificate");
            }
            return;
        }

        Map<String, Object> cert = certs.get(0);
        UUID certId         = (UUID)   cert.get("certificate_id");
        UUID pouchAccountId = (UUID)   cert.get("pouch_account_id");
        long issuedAmount   = ((Number) cert.get("issued_amount")).longValue();

        // 4. Process each transaction in counter-ascending order (§7.4)
        List<UUID> settledTxnIds = new ArrayList<>();
        List<SyncOfflineTxnRequest> transactions =
                batch.transactions() == null ? List.of() : batch.transactions();

        List<SyncOfflineTxnRequest> sorted = transactions.stream()
                .sorted(Comparator.comparingLong(SyncOfflineTxnRequest::counter))
                .toList();

        for (SyncOfflineTxnRequest txn : sorted) {
            boolean settled = settleOneTxn(txn, deviceId, certId, pouchAccountId, batchId, issuedAmount);
            if (settled) settledTxnIds.add(txn.offlineTxnId());
        }

        // 5. Post POUCH_REFUND, mark cert SETTLED, mark batch DONE — all in one TX
        long totalOutflow = totalSettledOutflow(certId);
        long unspent      = issuedAmount - totalOutflow;
        UUID senderOnlineAccountId = resolveUserOnlineAccountForDevice(deviceId);

        tx.executeWithoutResult(txStatus -> {
            if (unspent > 0) {
                ledger.post(new PostingRequest(
                        "POUCH_REFUND",
                        "CERTIFICATE",
                        certId.toString(),
                        "Pouch refund at sync for certificate " + certId,
                        List.of(
                                new LedgerEntry(pouchAccountId,         "DEBIT",  unspent),
                                new LedgerEntry(senderOnlineAccountId,  "CREDIT", unspent)
                        )
                ));
            }
            jdbc.update(
                    "UPDATE offline_certificates SET status = 'SETTLED', settled_at = now() " +
                    "WHERE certificate_id = ?", certId);
            jdbc.update(
                    "UPDATE sync_inbox SET status = 'DONE', processed_at = now() " +
                    "WHERE batch_id = ?", batchId);
        });

        // Notify device of settlement outcome — fire-and-forget, must not throw (§7.8)
        if (mqttPublisher != null) {
            mqttPublisher.publishSyncResult(deviceId.toString(), batchId.toString(), "SETTLED", null);
        }

        // 6. Flag all settled transactions if batch arrived after expiry
        if (syncedAfterExpiry) {
            for (UUID txnId : settledTxnIds) {
                insertFlag(txnId, batchId, certId, "EXPIRED_CERT_LATE_SYNC",
                        "Batch uploaded after certificate expiry");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Per-transaction settlement
    // -------------------------------------------------------------------------

    /**
     * Validates and commits one offline transaction.
     *
     * @return {@code true} if the transaction was settled (OFFLINE_TRANSFER posted);
     *         {@code false} if it was flagged and skipped
     */
    private boolean settleOneTxn(SyncOfflineTxnRequest txn, UUID senderDeviceId, UUID certId,
                                   UUID senderPouchAccountId, UUID batchId, long issuedAmount) {
        // Counter replay guard (§7.4)
        Long lastCounter = jdbc.queryForObject(
                "SELECT last_counter FROM devices WHERE device_id = ?", Long.class, senderDeviceId);
        if (lastCounter != null && txn.counter() <= lastCounter) {
            log.warn("Batch {} txn {} COUNTER_REPLAY: counter={} lastCounter={}",
                    batchId, txn.offlineTxnId(), txn.counter(), lastCounter);
            // offline_txn_id = null: no offline_transactions row exists for a replayed txn
            insertFlag(null, batchId, certId, "COUNTER_REPLAY",
                    "Counter " + txn.counter() + " <= last_counter " + lastCounter);
            return false;
        }

        // Build the canonical signing message
        String message = buildSigningMessage(txn, senderDeviceId);

        // Sender signature (§7 rule 9: never log the key bytes or signature)
        String senderPubKey = jdbc.queryForObject(
                "SELECT public_key FROM devices WHERE device_id = ?", String.class, senderDeviceId);
        if (!verifier.verify(message, txn.senderSignature(), senderPubKey)) {
            log.warn("Batch {} txn {} BAD_SIGNATURE: sender verification failed", batchId, txn.offlineTxnId());
            // offline_txn_id = null: no offline_transactions row exists for a rejected txn
            insertFlag(null, batchId, certId, "BAD_SIGNATURE", "Sender signature invalid");
            return false;
        }

        // Receiver signature
        String receiverPubKey;
        try {
            receiverPubKey = jdbc.queryForObject(
                    "SELECT public_key FROM devices WHERE device_id = ?", String.class, txn.receiverDeviceId());
        } catch (Exception e) {
            log.warn("Batch {} txn {} BAD_SIGNATURE: receiver device {} not found",
                    batchId, txn.offlineTxnId(), txn.receiverDeviceId());
            insertFlag(null, batchId, certId, "BAD_SIGNATURE",
                    "Receiver device not found: " + txn.receiverDeviceId());
            return false;
        }
        if (!verifier.verify(message, txn.receiverSignature(), receiverPubKey)) {
            log.warn("Batch {} txn {} BAD_SIGNATURE: receiver verification failed", batchId, txn.offlineTxnId());
            insertFlag(null, batchId, certId, "BAD_SIGNATURE", "Receiver signature invalid");
            return false;
        }

        // Outflow cap (§7.6) — uses committed outflows only (prior txns in this batch are committed)
        long existingOutflow = totalSettledOutflow(certId);
        if (existingOutflow + txn.amount() > issuedAmount) {
            log.warn("Batch {} txn {} OVER_LIMIT: running={} + amount={} > issued={}",
                    batchId, txn.offlineTxnId(), existingOutflow, txn.amount(), issuedAmount);
            // offline_txn_id = null: no offline_transactions row exists for a rejected txn
            insertFlag(null, batchId, certId, "OVER_LIMIT",
                    "Running outflow " + (existingOutflow + txn.amount()) +
                    " exceeds issued amount " + issuedAmount);
            return false;
        }

        // Resolve receiver's ONLINE account (belongs to the user who owns the receiver device)
        UUID receiverOnlineAccountId = resolveUserOnlineAccountForDevice(txn.receiverDeviceId());

        // Post OFFLINE_TRANSFER + insert offline_transactions + update last_counter — ONE DB TX (§7.2)
        tx.executeWithoutResult(txStatus -> {
            ledger.post(new PostingRequest(
                    "OFFLINE_TRANSFER",
                    "OFFLINE_TXN",
                    txn.offlineTxnId().toString(),
                    "Offline transfer " + txn.offlineTxnId(),
                    List.of(
                            new LedgerEntry(senderPouchAccountId,       "DEBIT",  txn.amount()),
                            new LedgerEntry(receiverOnlineAccountId,    "CREDIT", txn.amount())
                    )
            ));

            jdbc.update(
                    "INSERT INTO offline_transactions " +
                    "(offline_txn_id, sender_device_id, receiver_device_id, certificate_id, " +
                    " batch_id, amount, counter, device_timestamp, sender_signature, " +
                    " receiver_signature, settlement_status, settled_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SETTLED', now())",
                    txn.offlineTxnId(), senderDeviceId, txn.receiverDeviceId(), certId, batchId,
                    txn.amount(), txn.counter(),
                    txn.deviceTimestamp() != null ? Timestamp.from(txn.deviceTimestamp()) : null,
                    txn.senderSignature(), txn.receiverSignature());

            jdbc.update(
                    "UPDATE devices SET last_counter = ?, updated_at = now() WHERE device_id = ?",
                    txn.counter(), senderDeviceId);
        });

        return true;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Canonical signing message format agreed between device firmware and server.
     * Format: {@code "{offlineTxnId}|{senderDeviceId}|{receiverDeviceId}|{amount}|{counter}|{deviceTimestamp}"}
     * where deviceTimestamp is Instant.toString() (ISO-8601 UTC).
     */
    static String buildSigningMessage(SyncOfflineTxnRequest txn, UUID senderDeviceId) {
        String ts = txn.deviceTimestamp() != null ? txn.deviceTimestamp().toString() : "null";
        return txn.offlineTxnId() + "|" + senderDeviceId + "|" + txn.receiverDeviceId() + "|" +
               txn.amount() + "|" + txn.counter() + "|" + ts;
    }

    private long totalSettledOutflow(UUID certId) {
        Long total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM offline_transactions WHERE certificate_id = ?",
                Long.class, certId);
        return total == null ? 0L : total;
    }

    private UUID resolveUserOnlineAccountForDevice(UUID deviceId) {
        return jdbc.queryForObject(
                "SELECT a.account_id FROM accounts a " +
                "JOIN devices d ON a.user_id = d.user_id " +
                "WHERE d.device_id = ? AND a.type = 'ONLINE'",
                UUID.class, deviceId);
    }

    private void markFailed(UUID batchId, String reason) {
        jdbc.update(
                "UPDATE sync_inbox SET status = 'FAILED', error_reason = ? WHERE batch_id = ?",
                reason, batchId);
    }

    private void insertFlag(UUID offlineTxnId, UUID batchId, UUID certId, String reason, String detail) {
        jdbc.update(
                "INSERT INTO flagged_transactions " +
                "(offline_txn_id, batch_id, certificate_id, reason, detail) " +
                "VALUES (?, ?, ?, ?, ?)",
                offlineTxnId, batchId, certId, reason, detail);
    }
}
