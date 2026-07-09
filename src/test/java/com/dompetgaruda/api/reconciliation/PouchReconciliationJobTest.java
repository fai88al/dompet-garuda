package com.dompetgaruda.api.reconciliation;

import com.dompetgaruda.api.WorkerIntegrationTestBase;
import com.dompetgaruda.api.ledger.LedgerEntry;
import com.dompetgaruda.api.ledger.LedgerPostingService;
import com.dompetgaruda.api.ledger.PostingRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link PouchReconciliationJob}.
 *
 * <p>Runs with the {@code worker} profile and a real Testcontainers Postgres instance
 * (inherited from {@link WorkerIntegrationTestBase}). Tests call {@link
 * PouchReconciliationJob#reconcile()} directly to avoid the 1-hour scheduling delay.
 *
 * <p>Cases covered:
 * <ol>
 *   <li>Happy path — fully balanced certificate produces no RECON_MISMATCH flag.</li>
 *   <li>Mismatch detected — extra ledger DEBIT not in the expected calculation is flagged.</li>
 *   <li>No duplicate flags — reconcile() is idempotent for an already-flagged mismatch.</li>
 *   <li>SETTLED certificates are also audited.</li>
 *   <li>Zero ledger writes — reconciliation is read-only on ledger tables.</li>
 * </ol>
 */
class PouchReconciliationJobTest extends WorkerIntegrationTestBase {

    @Autowired PouchReconciliationJob job;
    @Autowired LedgerPostingService   ledger;
    @Autowired JdbcTemplate           jdbc;

    // Per-test identifiers, recreated in @BeforeEach
    private UUID senderUserId;
    private UUID senderDeviceId;
    private UUID senderOnlineAccountId;
    private UUID senderPouchAccountId;

    private UUID receiverUserId;
    private UUID receiverDeviceId;
    private UUID receiverOnlineAccountId;

    // All certificate_ids created in a test — cleaned up in @AfterEach
    private final List<UUID> certIds = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Setup / teardown
    // -------------------------------------------------------------------------

    @BeforeEach
    void setup() {
        // Tests call doReconcile() directly (the inner method, no ShedLock), mirroring the
        // SyncInboxPoller pattern where processOneRow() is called directly in tests and
        // pollInbox() (the @Scheduled entry point) is only called for ShedLock-specific tests.
        // This avoids a race between the background @Scheduled initial run and @BeforeEach.

        senderUserId   = insertUser("Sender Recon Test");
        senderDeviceId = insertDevice(senderUserId, "sender-recon-" + senderUserId.toString().substring(0, 8));
        senderOnlineAccountId = insertOnlineAccount(senderUserId);
        senderPouchAccountId  = insertPouchAccount(senderUserId, senderDeviceId);

        receiverUserId  = insertUser("Receiver Recon Test");
        receiverDeviceId = insertDevice(receiverUserId, "receiver-recon-" + receiverUserId.toString().substring(0, 8));
        receiverOnlineAccountId = insertOnlineAccount(receiverUserId);
    }

    @AfterEach
    void cleanup() {
        // FK order: flagged_transactions → offline_certificates → ledger_entries → ledger_transactions → accounts → devices → users

        // Delete flags for all certs created in this test
        for (UUID certId : certIds) {
            jdbc.update("DELETE FROM flagged_transactions WHERE certificate_id = ?", certId);
        }

        // Delete ledger entries that touched any test account, then orphaned transactions
        jdbc.update(
                "DELETE FROM ledger_entries WHERE transaction_id IN (" +
                "  SELECT DISTINCT transaction_id FROM ledger_entries " +
                "  WHERE account_id IN (?, ?, ?)" +
                ")",
                senderOnlineAccountId, senderPouchAccountId, receiverOnlineAccountId);
        jdbc.update(
                "DELETE FROM ledger_transactions WHERE transaction_id NOT IN " +
                "(SELECT DISTINCT transaction_id FROM ledger_entries)");

        // Delete certificates, then accounts, devices, users
        for (UUID certId : certIds) {
            jdbc.update("DELETE FROM offline_certificates WHERE certificate_id = ?", certId);
        }
        certIds.clear();

        jdbc.update("DELETE FROM accounts WHERE user_id IN (?, ?)", senderUserId, receiverUserId);
        jdbc.update("DELETE FROM devices WHERE device_id IN (?, ?)", senderDeviceId, receiverDeviceId);
        jdbc.update("DELETE FROM users WHERE user_id IN (?, ?)", senderUserId, receiverUserId);
    }

    // -------------------------------------------------------------------------
    // Test 1: Happy path — no mismatch expected
    // -------------------------------------------------------------------------

    @Test
    void reconcile_balancedCertificate_noFlagWritten() {
        // issued_amount = 100_000
        // POUCH_LOAD: CREDIT 100_000 to pouch
        // OFFLINE_TRANSFER: DEBIT 40_000 from pouch
        // POUCH_REFUND: DEBIT 60_000 from pouch
        // expected_remaining = 100_000 - 40_000 - 60_000 = 0
        // actual_remaining (pouch balance) = 100_000 - 40_000 - 60_000 = 0 → match

        UUID certId = insertActiveCert(100_000L, senderDeviceId, senderPouchAccountId);

        loadPouch(100_000L);
        postOfflineTransfer(40_000L);
        postPouchRefund(60_000L);

        long flagsBefore = countMismatchFlags(certId);
        job.doReconcile();
        long flagsAfter = countMismatchFlags(certId);

        assertThat(flagsAfter)
                .as("No RECON_MISMATCH flag for a balanced certificate")
                .isEqualTo(flagsBefore);
    }

    // -------------------------------------------------------------------------
    // Test 2: Mismatch detected
    // -------------------------------------------------------------------------

    @Test
    void reconcile_mismatch_flagsReconMismatch() {
        // Cert claims 200_000 was issued, but the ledger only shows 100_000 credited to the
        // pouch account (simulates a discrepancy between the certificate's authorised amount
        // and what actually landed in the ledger).
        //
        // settled_outflows = 0, refund_amount = 0
        // expected_remaining = 200_000 - 0 - 0 = 200_000
        // actual_remaining  (pouch ledger balance) = 100_000
        // 200_000 ≠ 100_000 → RECON_MISMATCH

        UUID certId = insertActiveCert(200_000L, senderDeviceId, senderPouchAccountId);
        loadPouch(100_000L);

        job.doReconcile();

        long count = countMismatchFlags(certId);
        assertThat(count)
                .as("Exactly one RECON_MISMATCH flag created for the mismatched certificate")
                .isEqualTo(1L);

        String detail = jdbc.queryForObject(
                "SELECT detail FROM flagged_transactions " +
                "WHERE certificate_id = ? AND reason = 'RECON_MISMATCH'",
                String.class, certId);
        assertThat(detail)
                .as("Flag detail must include expected and actual values")
                .contains("expected=200000")
                .contains("actual=100000");
    }

    // -------------------------------------------------------------------------
    // Test 3: No duplicate flags (idempotent)
    // -------------------------------------------------------------------------

    @Test
    void reconcile_calledTwiceOnMismatch_onlyOneFlagInserted() {
        UUID certId = insertActiveCert(200_000L, senderDeviceId, senderPouchAccountId);
        loadPouch(100_000L);

        // First run — creates the flag
        job.doReconcile();
        assertThat(countMismatchFlags(certId)).isEqualTo(1L);

        // Second run — must not insert a duplicate (resolved = false skips re-flagging)
        job.doReconcile();
        assertThat(countMismatchFlags(certId))
                .as("Reconciliation is idempotent: second run on same mismatch adds no new flag")
                .isEqualTo(1L);
    }

    // -------------------------------------------------------------------------
    // Test 4: SETTLED certificates are also checked
    // -------------------------------------------------------------------------

    @Test
    void reconcile_settledCertificateMismatch_isAlsoFlagged() {
        UUID certId = insertCertWithStatus(200_000L, senderDeviceId, senderPouchAccountId, "SETTLED");
        loadPouch(100_000L);

        job.doReconcile();

        assertThat(countMismatchFlags(certId))
                .as("RECON_MISMATCH must be flagged even for SETTLED certificates")
                .isEqualTo(1L);
    }

    // -------------------------------------------------------------------------
    // Test 5: Zero ledger writes
    // -------------------------------------------------------------------------

    @Test
    void reconcile_neverWritesToLedger() {
        // Set up a mismatch so reconciliation has work to do (certId tracked via certIds for cleanup)
        insertActiveCert(200_000L, senderDeviceId, senderPouchAccountId);
        loadPouch(100_000L);

        long entriesBefore = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long.class);
        long txnsBefore    = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transactions", Long.class);

        job.doReconcile();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long.class))
                .as("reconcile() must not write to ledger_entries (read-only on ledger, §7.1)")
                .isEqualTo(entriesBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transactions", Long.class))
                .as("reconcile() must not write to ledger_transactions")
                .isEqualTo(txnsBefore);
    }

    // -------------------------------------------------------------------------
    // Ledger helpers
    // -------------------------------------------------------------------------

    private void loadPouch(long amount) {
        ledger.post(new PostingRequest(
                "POUCH_LOAD", "TEST", "recon-test",
                "Recon test POUCH_LOAD",
                List.of(
                        new LedgerEntry(senderOnlineAccountId, "DEBIT",  amount),
                        new LedgerEntry(senderPouchAccountId,  "CREDIT", amount)
                )));
    }

    private void postOfflineTransfer(long amount) {
        ledger.post(new PostingRequest(
                "OFFLINE_TRANSFER", "TEST", "recon-test",
                "Recon test OFFLINE_TRANSFER",
                List.of(
                        new LedgerEntry(senderPouchAccountId,    "DEBIT",  amount),
                        new LedgerEntry(receiverOnlineAccountId, "CREDIT", amount)
                )));
    }

    private void postPouchRefund(long amount) {
        ledger.post(new PostingRequest(
                "POUCH_REFUND", "TEST", "recon-test",
                "Recon test POUCH_REFUND",
                List.of(
                        new LedgerEntry(senderPouchAccountId,  "DEBIT",  amount),
                        new LedgerEntry(senderOnlineAccountId, "CREDIT", amount)
                )));
    }

    // -------------------------------------------------------------------------
    // DB setup helpers
    // -------------------------------------------------------------------------

    private UUID insertActiveCert(long issuedAmount, UUID deviceId, UUID pouchAccountId) {
        return insertCertWithStatus(issuedAmount, deviceId, pouchAccountId, "ACTIVE");
    }

    private UUID insertCertWithStatus(long issuedAmount, UUID deviceId,
                                       UUID pouchAccountId, String status) {
        UUID certId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO offline_certificates " +
                "(certificate_id, device_id, pouch_account_id, issued_amount, " +
                " server_signature, status, expires_at) " +
                "VALUES (?, ?, ?, ?, 'test-sig', ?, now() + interval '24 hours')",
                certId, deviceId, pouchAccountId, issuedAmount, status);
        certIds.add(certId);
        return certId;
    }

    private UUID insertUser(String name) {
        UUID userId = UUID.randomUUID();
        String phone = "+6280" + System.nanoTime() % 1_000_000_000L;
        jdbc.update(
                "INSERT INTO users (user_id, full_name, phone) VALUES (?, ?, ?)",
                userId, name, phone);
        return userId;
    }

    private UUID insertDevice(UUID userId, String label) {
        UUID deviceId = UUID.randomUUID();
        String pubKey = "recon-pk-" + deviceId.toString().replace("-", "").substring(0, 16);
        String tokenHash = UUID.randomUUID().toString().replace("-", "")
                         + UUID.randomUUID().toString().replace("-", "");
        jdbc.update(
                "INSERT INTO devices (device_id, user_id, public_key, device_label, device_token_hash) " +
                "VALUES (?, ?, ?, ?, ?)",
                deviceId, userId, pubKey, label, tokenHash);
        return deviceId;
    }

    private UUID insertOnlineAccount(UUID userId) {
        UUID accountId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO accounts (account_id, user_id, type) VALUES (?, ?, 'ONLINE')",
                accountId, userId);
        return accountId;
    }

    private UUID insertPouchAccount(UUID userId, UUID deviceId) {
        UUID accountId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO accounts (account_id, user_id, device_id, type) VALUES (?, ?, ?, 'POUCH')",
                accountId, userId, deviceId);
        return accountId;
    }

    // -------------------------------------------------------------------------
    // Assertion helpers
    // -------------------------------------------------------------------------

    private long countMismatchFlags(UUID certId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flagged_transactions " +
                "WHERE certificate_id = ? AND reason = 'RECON_MISMATCH'",
                Long.class, certId);
        return count == null ? 0L : count;
    }
}
