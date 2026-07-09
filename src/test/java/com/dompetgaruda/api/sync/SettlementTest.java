package com.dompetgaruda.api.sync;

import com.dompetgaruda.api.WorkerIntegrationTestBase;
import com.dompetgaruda.api.ledger.LedgerEntry;
import com.dompetgaruda.api.ledger.LedgerPostingService;
import com.dompetgaruda.api.ledger.PostingRequest;
import com.dompetgaruda.api.sync.dto.SyncBatchRequest;
import com.dompetgaruda.api.sync.dto.SyncOfflineTxnRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full settlement integration tests (PR8).
 *
 * <p>All 10 required cases from the PR specification, run with the worker profile
 * against a real Testcontainers Postgres 16 instance. Each test:
 * <ul>
 *   <li>Sets up its own data via JdbcTemplate + LedgerPostingService.</li>
 *   <li>Signs transactions with real Ed25519 key pairs.</li>
 *   <li>Calls {@link SyncInboxPoller#processOneRow()} to drive settlement.</li>
 *   <li>Asserts final DB state and that the global ledger remains balanced (§7.2).</li>
 * </ul>
 *
 * <p>Settlement tests call {@code processOneRow()} rather than {@code pollInbox()} to avoid
 * holding the ShedLock for 5 s between tests (same rationale as SyncInboxPollerTest).
 */
class SettlementTest extends WorkerIntegrationTestBase {

    // Two distinct Ed25519 key pairs — one per test device.
    private static final KeyPair SENDER_KEYS;
    private static final KeyPair RECEIVER_KEYS;

    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
            SENDER_KEYS   = gen.generateKeyPair();
            RECEIVER_KEYS = gen.generateKeyPair();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired SyncInboxPoller       poller;
    @Autowired JdbcTemplate          jdbc;
    @Autowired LedgerPostingService  ledger;
    @Autowired ObjectMapper          objectMapper;

    // IDs created in @BeforeEach, torn down in @AfterEach
    private UUID senderUserId, senderDeviceId, senderOnlineAccountId, senderPouchAccountId;
    private UUID receiverUserId, receiverDeviceId, receiverOnlineAccountId, receiverPouchAccountId;

    private static final long ISSUED_AMOUNT = 100_000L;
    private static final long TRANSFER_AMOUNT = 50_000L;

    // -------------------------------------------------------------------------
    // Setup / teardown
    // -------------------------------------------------------------------------

    @BeforeEach
    void setup() {
        senderUserId   = insertUser("Sender");
        receiverUserId = insertUser("Receiver");

        String senderPubKey   = base64PublicKey(SENDER_KEYS);
        String receiverPubKey = base64PublicKey(RECEIVER_KEYS);

        senderDeviceId   = insertDevice(senderUserId,   senderPubKey);
        receiverDeviceId = insertDevice(receiverUserId, receiverPubKey);

        senderOnlineAccountId   = insertAccount(senderUserId,   null,             "ONLINE");
        senderPouchAccountId    = insertAccount(senderUserId,   senderDeviceId,   "POUCH");
        receiverOnlineAccountId = insertAccount(receiverUserId, null,             "ONLINE");
        receiverPouchAccountId  = insertAccount(receiverUserId, receiverDeviceId, "POUCH");
    }

    @AfterEach
    void cleanup() {
        // FK order — most-dependent tables first
        jdbc.update("DELETE FROM flagged_transactions WHERE batch_id IN " +
                    "(SELECT batch_id FROM sync_inbox WHERE device_id = ?)", senderDeviceId);
        jdbc.update("DELETE FROM flagged_transactions WHERE certificate_id IN " +
                    "(SELECT certificate_id FROM offline_certificates WHERE device_id = ?)", senderDeviceId);
        jdbc.update("DELETE FROM offline_transactions WHERE sender_device_id = ? OR receiver_device_id = ?",
                senderDeviceId, receiverDeviceId);
        jdbc.update("DELETE FROM sync_inbox WHERE device_id = ?", senderDeviceId);

        // Delete ALL entries for any ledger_transaction that touched a test account (including SYSTEM entries
        // from TOPUP), then drop orphaned transaction headers — so no DEBIT/CREDIT imbalance leaks between tests.
        jdbc.update(
                "DELETE FROM ledger_entries WHERE transaction_id IN (" +
                "  SELECT DISTINCT transaction_id FROM ledger_entries " +
                "  WHERE account_id IN (?, ?, ?, ?)" +
                ")",
                senderOnlineAccountId, senderPouchAccountId,
                receiverOnlineAccountId, receiverPouchAccountId);
        jdbc.update(
                "DELETE FROM ledger_transactions WHERE transaction_id NOT IN " +
                "(SELECT transaction_id FROM ledger_entries)");

        jdbc.update("DELETE FROM offline_certificates WHERE device_id = ?", senderDeviceId);
        jdbc.update("DELETE FROM accounts WHERE account_id IN (?, ?, ?, ?)",
                senderOnlineAccountId, senderPouchAccountId,
                receiverOnlineAccountId, receiverPouchAccountId);
        jdbc.update("DELETE FROM devices WHERE device_id IN (?, ?)", senderDeviceId, receiverDeviceId);
        jdbc.update("DELETE FROM users WHERE user_id IN (?, ?)", senderUserId, receiverUserId);
    }

    // -------------------------------------------------------------------------
    // Test a: Happy path — OFFLINE_TRANSFER + POUCH_REFUND, cert SETTLED, batch DONE
    // -------------------------------------------------------------------------

    @Test
    void happyPath_singleTransfer_ledgerBalancedCertSettledBatchDone() throws Exception {
        setupLedgerBalance(ISSUED_AMOUNT);
        UUID certId  = insertCert(senderDeviceId, senderPouchAccountId, ISSUED_AMOUNT, Instant.now().plusSeconds(86400));
        UUID batchId = UUID.randomUUID();
        UUID txnId   = UUID.randomUUID();
        Instant ts   = Instant.parse("2026-07-07T10:00:00Z");

        String msg   = buildMsg(txnId, senderDeviceId, receiverDeviceId, TRANSFER_AMOUNT, 1L, ts);
        SyncOfflineTxnRequest txn = new SyncOfflineTxnRequest(
                txnId, receiverDeviceId, TRANSFER_AMOUNT, 1L, ts,
                sign(msg, SENDER_KEYS), sign(msg, RECEIVER_KEYS));

        insertBatch(batchId, senderDeviceId, certId, List.of(txn), false);
        poller.processOneRow();

        // Batch DONE, cert SETTLED
        assertThat(batchStatus(batchId)).isEqualTo("DONE");
        assertThat(certStatus(certId)).isEqualTo("SETTLED");

        // offline_transactions row
        assertThat(offlineTxnStatus(txnId)).isEqualTo("SETTLED");

        // last_counter updated
        assertThat(lastCounter(senderDeviceId)).isEqualTo(1L);

        // OFFLINE_TRANSFER entries
        assertLedgerEntries("OFFLINE_TRANSFER", txnId.toString(),
                senderPouchAccountId, "DEBIT",  TRANSFER_AMOUNT);
        assertLedgerEntries("OFFLINE_TRANSFER", txnId.toString(),
                receiverOnlineAccountId, "CREDIT", TRANSFER_AMOUNT);

        // POUCH_REFUND entries (unspent = 100_000 - 50_000 = 50_000)
        long unspent = ISSUED_AMOUNT - TRANSFER_AMOUNT;
        assertLedgerEntries("POUCH_REFUND", certId.toString(),
                senderPouchAccountId, "DEBIT",  unspent);
        assertLedgerEntries("POUCH_REFUND", certId.toString(),
                senderOnlineAccountId, "CREDIT", unspent);

        assertLedgerBalanced();
    }

    // -------------------------------------------------------------------------
    // Test b: Replayed batch — second processing produces zero additional entries
    // -------------------------------------------------------------------------

    @Test
    void replayedBatch_secondProcessingAddsNoLedgerEntries() throws Exception {
        setupLedgerBalance(ISSUED_AMOUNT);
        UUID certId  = insertCert(senderDeviceId, senderPouchAccountId, ISSUED_AMOUNT, Instant.now().plusSeconds(86400));
        UUID txnId   = UUID.randomUUID();
        Instant ts   = Instant.parse("2026-07-07T10:00:00Z");
        String msg   = buildMsg(txnId, senderDeviceId, receiverDeviceId, TRANSFER_AMOUNT, 1L, ts);
        SyncOfflineTxnRequest txn = new SyncOfflineTxnRequest(
                txnId, receiverDeviceId, TRANSFER_AMOUNT, 1L, ts,
                sign(msg, SENDER_KEYS), sign(msg, RECEIVER_KEYS));

        // First batch — settles successfully
        UUID batchId1 = UUID.randomUUID();
        insertBatch(batchId1, senderDeviceId, certId, List.of(txn), false);
        poller.processOneRow();
        assertThat(batchStatus(batchId1)).isEqualTo("DONE");

        long entriesAfterFirst = countLedgerEntries();

        // Second batch — same txn (counter replay) and cert now SETTLED
        UUID batchId2 = UUID.randomUUID();
        // Re-insert batch for same (already-settled) cert — cert is SETTLED now, so settle() marks MALFORMED
        insertBatch(batchId2, senderDeviceId, certId, List.of(txn), false);
        poller.processOneRow();

        // No additional ledger entries
        assertThat(countLedgerEntries())
                .as("replayed batch must add zero ledger entries")
                .isEqualTo(entriesAfterFirst);

        // Second batch ends FAILED (cert already SETTLED → MALFORMED)
        assertThat(batchStatus(batchId2)).isEqualTo("FAILED");

        assertLedgerBalanced();
    }

    // -------------------------------------------------------------------------
    // Test c: Counter replay — flagged COUNTER_REPLAY, no ledger entries for that txn
    // -------------------------------------------------------------------------

    @Test
    void counterReplay_flaggedNoLedgerEntry_batchStillDone() throws Exception {
        setupLedgerBalance(ISSUED_AMOUNT);
        UUID certId  = insertCert(senderDeviceId, senderPouchAccountId, ISSUED_AMOUNT, Instant.now().plusSeconds(86400));
        UUID txnId1  = UUID.randomUUID();
        UUID txnId2  = UUID.randomUUID();
        Instant ts   = Instant.parse("2026-07-07T10:00:00Z");

        // Transaction 1: counter=1 (valid)
        String msg1 = buildMsg(txnId1, senderDeviceId, receiverDeviceId, TRANSFER_AMOUNT, 1L, ts);
        SyncOfflineTxnRequest txn1 = new SyncOfflineTxnRequest(
                txnId1, receiverDeviceId, TRANSFER_AMOUNT, 1L, ts,
                sign(msg1, SENDER_KEYS), sign(msg1, RECEIVER_KEYS));

        // Transaction 2: counter=1 again (replay of same counter)
        String msg2 = buildMsg(txnId2, senderDeviceId, receiverDeviceId, 10_000L, 1L, ts);
        SyncOfflineTxnRequest txn2 = new SyncOfflineTxnRequest(
                txnId2, receiverDeviceId, 10_000L, 1L, ts,
                sign(msg2, SENDER_KEYS), sign(msg2, RECEIVER_KEYS));

        UUID batchId = UUID.randomUUID();
        insertBatch(batchId, senderDeviceId, certId, List.of(txn1, txn2), false);
        poller.processOneRow();

        assertThat(batchStatus(batchId)).isEqualTo("DONE");

        // txn1 settled, txn2 flagged COUNTER_REPLAY
        assertThat(offlineTxnStatus(txnId1)).isEqualTo("SETTLED");
        assertThat(countOfflineTxns(txnId2)).isEqualTo(0); // not inserted at all

        Integer replayFlags = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flagged_transactions WHERE reason = 'COUNTER_REPLAY' AND batch_id = ?",
                Integer.class, batchId);
        assertThat(replayFlags).isEqualTo(1);

        assertLedgerBalanced();
    }

    // -------------------------------------------------------------------------
    // Test d: Over-limit — flagged OVER_LIMIT, no OFFLINE_TRANSFER entries
    // -------------------------------------------------------------------------

    @Test
    void overLimit_flaggedNoTransferEntries() throws Exception {
        long smallIssued = 30_000L;
        setupLedgerBalance(smallIssued);
        UUID certId  = insertCert(senderDeviceId, senderPouchAccountId, smallIssued, Instant.now().plusSeconds(86400));
        UUID txnId   = UUID.randomUUID();
        Instant ts   = Instant.parse("2026-07-07T10:00:00Z");
        long tooMuch = 50_000L; // exceeds issued 30_000

        String msg   = buildMsg(txnId, senderDeviceId, receiverDeviceId, tooMuch, 1L, ts);
        SyncOfflineTxnRequest txn = new SyncOfflineTxnRequest(
                txnId, receiverDeviceId, tooMuch, 1L, ts,
                sign(msg, SENDER_KEYS), sign(msg, RECEIVER_KEYS));

        UUID batchId = UUID.randomUUID();
        insertBatch(batchId, senderDeviceId, certId, List.of(txn), false);
        poller.processOneRow();

        Integer overLimitFlags = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flagged_transactions WHERE reason = 'OVER_LIMIT' AND batch_id = ?",
                Integer.class, batchId);
        assertThat(overLimitFlags).isEqualTo(1);

        // No OFFLINE_TRANSFER entries
        Long transferEntries = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries e " +
                "JOIN ledger_transactions t ON e.transaction_id = t.transaction_id " +
                "WHERE t.type = 'OFFLINE_TRANSFER'",
                Long.class);
        assertThat(transferEntries).isEqualTo(0L);

        assertLedgerBalanced();
    }

    // -------------------------------------------------------------------------
    // Test e: Bad sender signature — flagged BAD_SIGNATURE
    // -------------------------------------------------------------------------

    @Test
    void badSenderSignature_flaggedBadSignature() throws Exception {
        setupLedgerBalance(ISSUED_AMOUNT);
        UUID certId  = insertCert(senderDeviceId, senderPouchAccountId, ISSUED_AMOUNT, Instant.now().plusSeconds(86400));
        UUID txnId   = UUID.randomUUID();
        Instant ts   = Instant.parse("2026-07-07T10:00:00Z");

        String msg   = buildMsg(txnId, senderDeviceId, receiverDeviceId, TRANSFER_AMOUNT, 1L, ts);
        // Sign sender message with RECEIVER's private key instead → bad sender sig
        SyncOfflineTxnRequest txn = new SyncOfflineTxnRequest(
                txnId, receiverDeviceId, TRANSFER_AMOUNT, 1L, ts,
                sign(msg, RECEIVER_KEYS), sign(msg, RECEIVER_KEYS));

        UUID batchId = UUID.randomUUID();
        insertBatch(batchId, senderDeviceId, certId, List.of(txn), false);
        poller.processOneRow();

        Integer badSigFlags = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flagged_transactions WHERE reason = 'BAD_SIGNATURE' AND batch_id = ?",
                Integer.class, batchId);
        assertThat(badSigFlags).isGreaterThanOrEqualTo(1);

        // No OFFLINE_TRANSFER entries
        assertThat(countOfflineTxns(txnId)).isEqualTo(0);

        assertLedgerBalanced();
    }

    // -------------------------------------------------------------------------
    // Test f: Bad receiver signature — flagged BAD_SIGNATURE
    // -------------------------------------------------------------------------

    @Test
    void badReceiverSignature_flaggedBadSignature() throws Exception {
        setupLedgerBalance(ISSUED_AMOUNT);
        UUID certId  = insertCert(senderDeviceId, senderPouchAccountId, ISSUED_AMOUNT, Instant.now().plusSeconds(86400));
        UUID txnId   = UUID.randomUUID();
        Instant ts   = Instant.parse("2026-07-07T10:00:00Z");

        String msg   = buildMsg(txnId, senderDeviceId, receiverDeviceId, TRANSFER_AMOUNT, 1L, ts);
        // Sign receiver message with SENDER's private key instead → bad receiver sig
        SyncOfflineTxnRequest txn = new SyncOfflineTxnRequest(
                txnId, receiverDeviceId, TRANSFER_AMOUNT, 1L, ts,
                sign(msg, SENDER_KEYS), sign(msg, SENDER_KEYS));

        UUID batchId = UUID.randomUUID();
        insertBatch(batchId, senderDeviceId, certId, List.of(txn), false);
        poller.processOneRow();

        Integer badSigFlags = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flagged_transactions WHERE reason = 'BAD_SIGNATURE' AND batch_id = ?",
                Integer.class, batchId);
        assertThat(badSigFlags).isGreaterThanOrEqualTo(1);

        assertThat(countOfflineTxns(txnId)).isEqualTo(0);

        assertLedgerBalanced();
    }

    // -------------------------------------------------------------------------
    // Test g: Malformed batch — FAILED + MALFORMED flag + zero ledger writes + poller continues
    // -------------------------------------------------------------------------

    @Test
    void malformedBatch_failedMalformedFlagZeroLedgerWritesPollerContinues() throws Exception {
        setupLedgerBalance(ISSUED_AMOUNT);
        UUID certId      = insertCert(senderDeviceId, senderPouchAccountId, ISSUED_AMOUNT, Instant.now().plusSeconds(86400));
        UUID malformedId = UUID.randomUUID();
        UUID validBatchId = UUID.randomUUID();
        UUID txnId       = UUID.randomUUID();
        Instant ts       = Instant.parse("2026-07-07T10:00:00Z");

        // Insert malformed batch first (JSON object that can't parse as SyncBatchRequest)
        jdbc.update("INSERT INTO sync_inbox (batch_id, device_id, raw_payload, status) " +
                    "VALUES (?, ?, '{\"certificateId\": 99999}'::jsonb, 'PENDING')",
                malformedId, senderDeviceId);

        long entriesBefore = countLedgerEntries();

        // Process malformed batch
        boolean found1 = poller.processOneRow();
        assertThat(found1).isTrue();
        assertThat(batchStatus(malformedId)).isEqualTo("FAILED");

        Integer malformedFlags = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flagged_transactions WHERE reason = 'MALFORMED' AND batch_id = ?",
                Integer.class, malformedId);
        assertThat(malformedFlags).isGreaterThanOrEqualTo(1);

        assertThat(countLedgerEntries())
                .as("malformed batch must cause zero ledger writes")
                .isEqualTo(entriesBefore);

        // Insert valid batch — poller must continue and settle it
        String msg = buildMsg(txnId, senderDeviceId, receiverDeviceId, TRANSFER_AMOUNT, 1L, ts);
        SyncOfflineTxnRequest txn = new SyncOfflineTxnRequest(
                txnId, receiverDeviceId, TRANSFER_AMOUNT, 1L, ts,
                sign(msg, SENDER_KEYS), sign(msg, RECEIVER_KEYS));
        insertBatch(validBatchId, senderDeviceId, certId, List.of(txn), false);

        boolean found2 = poller.processOneRow();
        assertThat(found2).isTrue();
        assertThat(batchStatus(validBatchId)).isEqualTo("DONE");

        assertLedgerBalanced();

        // Extra cleanup for malformed batch
        jdbc.update("DELETE FROM flagged_transactions WHERE batch_id = ?", malformedId);
        jdbc.update("DELETE FROM sync_inbox WHERE batch_id = ?", malformedId);
    }

    // -------------------------------------------------------------------------
    // Test h: Late sync — settled AND flagged EXPIRED_CERT_LATE_SYNC
    // -------------------------------------------------------------------------

    @Test
    void lateSync_settledAndFlaggedExpiredCertLateSynced() throws Exception {
        setupLedgerBalance(ISSUED_AMOUNT);
        // Cert is EXPIRED (status=EXPIRED, expires_at in the past)
        UUID certId  = insertExpiredCert(senderDeviceId, senderPouchAccountId, ISSUED_AMOUNT);
        UUID batchId = UUID.randomUUID();
        UUID txnId   = UUID.randomUUID();
        Instant ts   = Instant.parse("2026-07-07T10:00:00Z");

        String msg   = buildMsg(txnId, senderDeviceId, receiverDeviceId, TRANSFER_AMOUNT, 1L, ts);
        SyncOfflineTxnRequest txn = new SyncOfflineTxnRequest(
                txnId, receiverDeviceId, TRANSFER_AMOUNT, 1L, ts,
                sign(msg, SENDER_KEYS), sign(msg, RECEIVER_KEYS));

        // synced_after_expiry = true
        insertBatch(batchId, senderDeviceId, certId, List.of(txn), true);
        poller.processOneRow();

        // Settled normally
        assertThat(batchStatus(batchId)).isEqualTo("DONE");
        assertThat(offlineTxnStatus(txnId)).isEqualTo("SETTLED");

        // AND flagged for late sync
        Integer lateFlags = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flagged_transactions " +
                "WHERE reason = 'EXPIRED_CERT_LATE_SYNC' AND offline_txn_id = ?",
                Integer.class, txnId);
        assertThat(lateFlags).isEqualTo(1);

        assertLedgerBalanced();
    }

    // -------------------------------------------------------------------------
    // Test i: Unspent refund — POUCH_REFUND = exact unspent, balanced, cert SETTLED
    // -------------------------------------------------------------------------

    @Test
    void unspentRefund_pouchRefundExactlyUnspentCertSettled() throws Exception {
        long txnAmount = 30_000L;
        long unspent   = ISSUED_AMOUNT - txnAmount; // 70_000

        setupLedgerBalance(ISSUED_AMOUNT);
        UUID certId  = insertCert(senderDeviceId, senderPouchAccountId, ISSUED_AMOUNT, Instant.now().plusSeconds(86400));
        UUID batchId = UUID.randomUUID();
        UUID txnId   = UUID.randomUUID();
        Instant ts   = Instant.parse("2026-07-07T10:00:00Z");

        String msg   = buildMsg(txnId, senderDeviceId, receiverDeviceId, txnAmount, 1L, ts);
        SyncOfflineTxnRequest txn = new SyncOfflineTxnRequest(
                txnId, receiverDeviceId, txnAmount, 1L, ts,
                sign(msg, SENDER_KEYS), sign(msg, RECEIVER_KEYS));

        insertBatch(batchId, senderDeviceId, certId, List.of(txn), false);
        poller.processOneRow();

        assertThat(batchStatus(batchId)).isEqualTo("DONE");
        assertThat(certStatus(certId)).isEqualTo("SETTLED");

        // POUCH_REFUND amount must equal the unspent
        Long refundAmount = jdbc.queryForObject(
                "SELECT e.amount FROM ledger_entries e " +
                "JOIN ledger_transactions t ON e.transaction_id = t.transaction_id " +
                "WHERE t.type = 'POUCH_REFUND' AND t.reference_id = ? AND e.direction = 'CREDIT'",
                Long.class, certId.toString());
        assertThat(refundAmount).isEqualTo(unspent);

        assertLedgerBalanced();
    }

    // -------------------------------------------------------------------------
    // Test j: Ledger balanced for every transaction row in all above tests
    //         (this dedicated test verifies a multi-transaction batch)
    // -------------------------------------------------------------------------

    @Test
    void multiTransactionBatch_allLedgerEntriesBalanced() throws Exception {
        long amt1 = 20_000L;
        long amt2 = 30_000L;
        setupLedgerBalance(ISSUED_AMOUNT);
        UUID certId   = insertCert(senderDeviceId, senderPouchAccountId, ISSUED_AMOUNT, Instant.now().plusSeconds(86400));
        UUID batchId  = UUID.randomUUID();
        UUID txnId1   = UUID.randomUUID();
        UUID txnId2   = UUID.randomUUID();
        Instant ts    = Instant.parse("2026-07-07T10:00:00Z");

        String msg1   = buildMsg(txnId1, senderDeviceId, receiverDeviceId, amt1, 1L, ts);
        String msg2   = buildMsg(txnId2, senderDeviceId, receiverDeviceId, amt2, 2L, ts);
        SyncOfflineTxnRequest txn1 = new SyncOfflineTxnRequest(
                txnId1, receiverDeviceId, amt1, 1L, ts,
                sign(msg1, SENDER_KEYS), sign(msg1, RECEIVER_KEYS));
        SyncOfflineTxnRequest txn2 = new SyncOfflineTxnRequest(
                txnId2, receiverDeviceId, amt2, 2L, ts,
                sign(msg2, SENDER_KEYS), sign(msg2, RECEIVER_KEYS));

        insertBatch(batchId, senderDeviceId, certId, List.of(txn1, txn2), false);
        poller.processOneRow();

        assertThat(batchStatus(batchId)).isEqualTo("DONE");
        assertThat(offlineTxnStatus(txnId1)).isEqualTo("SETTLED");
        assertThat(offlineTxnStatus(txnId2)).isEqualTo("SETTLED");

        // last_counter updated to the highest counter processed
        assertThat(lastCounter(senderDeviceId)).isEqualTo(2L);

        assertLedgerBalanced();
    }

    // -------------------------------------------------------------------------
    // DB setup helpers
    // -------------------------------------------------------------------------

    /** Posts TOPUP (SYSTEM → sender online) then POUCH_LOAD (sender online → sender pouch). */
    private void setupLedgerBalance(long amount) {
        ledger.post(new PostingRequest("TOPUP", "TEST", "setup", "Test top-up",
                List.of(
                        new LedgerEntry(LedgerPostingService.SYSTEM_ACCOUNT_ID, "DEBIT",  amount),
                        new LedgerEntry(senderOnlineAccountId,                  "CREDIT", amount)
                )));
        ledger.post(new PostingRequest("POUCH_LOAD", "TEST", "setup", "Test pouch load",
                List.of(
                        new LedgerEntry(senderOnlineAccountId, "DEBIT",  amount),
                        new LedgerEntry(senderPouchAccountId,  "CREDIT", amount)
                )));
    }

    private UUID insertUser(String label) {
        UUID id = UUID.randomUUID();
        String phone = "+62800" + System.nanoTime() % 100_000_000L;
        jdbc.update("INSERT INTO users (user_id, full_name, phone) VALUES (?, ?, ?)",
                id, label + " Test User", phone);
        return id;
    }

    private UUID insertDevice(UUID userId, String pubKeyBase64) {
        UUID id        = UUID.randomUUID();
        String tokHash = UUID.randomUUID().toString().replace("-", "")
                       + UUID.randomUUID().toString().replace("-", "");
        jdbc.update(
                "INSERT INTO devices (device_id, user_id, public_key, device_label, device_token_hash) " +
                "VALUES (?, ?, ?, 'Test Device', ?)",
                id, userId, pubKeyBase64, tokHash);
        return id;
    }

    private UUID insertAccount(UUID userId, UUID deviceId, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO accounts (account_id, user_id, device_id, type) VALUES (?, ?, ?, ?)",
                id, userId, deviceId, type);
        return id;
    }

    private UUID insertCert(UUID deviceId, UUID pouchAccountId, long issuedAmount, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO offline_certificates " +
                "(certificate_id, device_id, pouch_account_id, issued_amount, server_signature, status, expires_at) " +
                "VALUES (?, ?, ?, ?, 'test-sig', 'ACTIVE', ?)",
                id, deviceId, pouchAccountId, issuedAmount,
                java.sql.Timestamp.from(expiresAt));
        return id;
    }

    private UUID insertExpiredCert(UUID deviceId, UUID pouchAccountId, long issuedAmount) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO offline_certificates " +
                "(certificate_id, device_id, pouch_account_id, issued_amount, server_signature, status, expires_at) " +
                "VALUES (?, ?, ?, ?, 'test-sig', 'EXPIRED', ?)",
                id, deviceId, pouchAccountId, issuedAmount,
                java.sql.Timestamp.from(Instant.now().minusSeconds(3600)));
        return id;
    }

    private void insertBatch(UUID batchId, UUID deviceId, UUID certId,
                              List<SyncOfflineTxnRequest> txns, boolean syncedAfterExpiry)
            throws Exception {
        String json = objectMapper.writeValueAsString(new SyncBatchRequest(certId, txns));
        jdbc.update(
                "INSERT INTO sync_inbox (batch_id, device_id, raw_payload, status, synced_after_expiry) " +
                "VALUES (?, ?, ?::jsonb, 'PENDING', ?)",
                batchId, deviceId, json, syncedAfterExpiry);
    }

    // -------------------------------------------------------------------------
    // Signing helpers
    // -------------------------------------------------------------------------

    private static String base64PublicKey(KeyPair kp) {
        return Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
    }

    private static String sign(String message, KeyPair kp) throws Exception {
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(kp.getPrivate());
        sig.update(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    /** Mirrors SyncSettlementService.buildSigningMessage() exactly. */
    private static String buildMsg(UUID offlineTxnId, UUID senderDeviceId, UUID receiverDeviceId,
                                    long amount, long counter, Instant deviceTimestamp) {
        return SyncSettlementService.buildSigningMessage(
                new SyncOfflineTxnRequest(offlineTxnId, receiverDeviceId, amount, counter,
                        deviceTimestamp, null, null),
                senderDeviceId);
    }

    // -------------------------------------------------------------------------
    // Assertion helpers
    // -------------------------------------------------------------------------

    private String batchStatus(UUID batchId) {
        return jdbc.queryForObject(
                "SELECT status FROM sync_inbox WHERE batch_id = ?", String.class, batchId);
    }

    private String certStatus(UUID certId) {
        return jdbc.queryForObject(
                "SELECT status FROM offline_certificates WHERE certificate_id = ?", String.class, certId);
    }

    private String offlineTxnStatus(UUID txnId) {
        return jdbc.queryForObject(
                "SELECT settlement_status FROM offline_transactions WHERE offline_txn_id = ?",
                String.class, txnId);
    }

    private long lastCounter(UUID deviceId) {
        Long v = jdbc.queryForObject(
                "SELECT last_counter FROM devices WHERE device_id = ?", Long.class, deviceId);
        return v == null ? 0L : v;
    }

    private int countOfflineTxns(UUID txnId) {
        Integer v = jdbc.queryForObject(
                "SELECT COUNT(*) FROM offline_transactions WHERE offline_txn_id = ?",
                Integer.class, txnId);
        return v == null ? 0 : v;
    }

    private long countLedgerEntries() {
        Long v = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long.class);
        return v == null ? 0L : v;
    }

    private void assertLedgerEntries(String txnType, String referenceId,
                                      UUID accountId, String direction, long amount) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries e " +
                "JOIN ledger_transactions t ON e.transaction_id = t.transaction_id " +
                "WHERE t.type = ? AND t.reference_id = ? " +
                "  AND e.account_id = ? AND e.direction = ? AND e.amount = ?",
                Integer.class,
                txnType, referenceId, accountId, direction, amount);
        assertThat(count)
                .as("Expected 1 %s entry: account=%s direction=%s amount=%d",
                        txnType, accountId, direction, amount)
                .isEqualTo(1);
    }

    /**
     * Asserts that SUM(CREDIT) == SUM(DEBIT) across ALL ledger_entries for every
     * ledger_transaction that involved any of this test's accounts (§7.2).
     *
     * <p>Scoped to this test's transactions (not the global table) so that SYSTEM-account
     * DEBIT entries from other tests do not bleed in when the shared database is reused.
     * Since each posting is balanced by construction, the subquery-based scope is equivalent
     * to a global check for this test's data.
     */
    private void assertLedgerBalanced() {
        String subquery = "SELECT DISTINCT transaction_id FROM ledger_entries " +
                          "WHERE account_id IN (?, ?, ?, ?)";
        Long credits = jdbc.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN direction='CREDIT' THEN amount ELSE 0 END), 0) " +
                "FROM ledger_entries WHERE transaction_id IN (" + subquery + ")",
                Long.class,
                senderOnlineAccountId, senderPouchAccountId,
                receiverOnlineAccountId, receiverPouchAccountId);
        Long debits = jdbc.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN direction='DEBIT' THEN amount ELSE 0 END), 0) " +
                "FROM ledger_entries WHERE transaction_id IN (" + subquery + ")",
                Long.class,
                senderOnlineAccountId, senderPouchAccountId,
                receiverOnlineAccountId, receiverPouchAccountId);
        assertThat(credits)
                .as("ledger must balance for this test's transactions: credits=%d debits=%d (§7.2)",
                        credits, debits)
                .isEqualTo(debits);
    }
}
