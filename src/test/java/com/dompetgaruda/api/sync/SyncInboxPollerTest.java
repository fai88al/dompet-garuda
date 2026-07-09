package com.dompetgaruda.api.sync;

import com.dompetgaruda.api.WorkerIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration tests for the worker inbox poller.
 *
 * <p>Runs with the {@code worker} profile and a real Testcontainers Postgres instance.
 *
 * <p>Processing tests call {@link SyncInboxPoller#processOneRow()} directly rather than
 * {@link SyncInboxPoller#pollInbox()}.  {@code pollInbox()} carries a ShedLock with
 * {@code lockAtLeastFor = "PT5S"}, so calling it in every test would hold the distributed
 * lock for 5 s per test and silently skip later tests that also try to acquire it.
 * {@code processOneRow()} has no lock and exercises the same logic.  Only the ShedLock
 * test calls {@code pollInbox()} so it can verify the lock row is written.
 *
 * <p>Cases covered:
 * <ol>
 *   <li>Malformed batch (invalid JSON for SyncBatchRequest) → FAILED + MALFORMED flag, no ledger writes.</li>
 *   <li>Empty inbox → returns {@code false}, no exception.</li>
 *   <li>Multiple PENDING rows → each settles to FAILED (malformed), processOneRow returns true each time.</li>
 *   <li>ShedLock row written to {@code shedlock} table after {@code pollInbox()} runs (§7 invariant 7).</li>
 *   <li>Zero ledger writes for malformed batch (§7 rule 5).</li>
 * </ol>
 */
class SyncInboxPollerTest extends WorkerIntegrationTestBase {

    @Autowired SyncInboxPoller poller;
    @Autowired JdbcTemplate    jdbc;

    // -------------------------------------------------------------------------
    // Malformed batch → FAILED (no stub reset — real settlement rejects bad JSON)
    // -------------------------------------------------------------------------

    @Test
    void processOneRow_malformedBatch_setsStatusFailed() {
        UUID deviceId = insertDevice();
        UUID batchId  = insertPendingBatch(deviceId);

        boolean found = poller.processOneRow();

        assertThat(found).isTrue();
        String status = jdbc.queryForObject(
                "SELECT status FROM sync_inbox WHERE batch_id = ?", String.class, batchId);
        // Settlement fails to parse '[]' as SyncBatchRequest → FAILED + MALFORMED flag
        assertThat(status).isEqualTo("FAILED");

        cleanup(batchId, deviceId);
    }

    // -------------------------------------------------------------------------
    // Empty inbox
    // -------------------------------------------------------------------------

    @Test
    void processOneRow_emptyInbox_returnsFalseWithoutException() {
        assertThatCode(() -> {
            boolean found = poller.processOneRow();
            assertThat(found).isFalse();
        }).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Queue draining — multiple rows processed sequentially
    // -------------------------------------------------------------------------

    @Test
    void processOneRow_multiplePendingRows_returnsTrueEachTime() {
        UUID deviceId = insertDevice();
        UUID batchId1 = insertPendingBatch(deviceId);
        UUID batchId2 = insertPendingBatch(deviceId);

        assertThat(poller.processOneRow()).isTrue();
        assertThat(poller.processOneRow()).isTrue();

        // Both rows end up FAILED (malformed JSON → settlement fails → FAILED)
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sync_inbox WHERE device_id = ? AND status = 'PENDING'",
                Integer.class, deviceId);
        assertThat(pending).isEqualTo(0);

        cleanupDevice(deviceId);
    }

    // -------------------------------------------------------------------------
    // ShedLock — §7 invariant 7: every @Scheduled job must have ShedLock
    // -------------------------------------------------------------------------

    @Test
    void pollInbox_afterExecution_shedlockRowExists() {
        // Call pollInbox() on an empty inbox — no rows to process, runs quickly,
        // but ShedLock still acquires and releases the distributed lock.
        poller.pollInbox();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shedlock WHERE name = 'sync-inbox-poller'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Zero ledger writes — §7 rule 5
    // -------------------------------------------------------------------------

    @Test
    void processOneRow_malformedBatch_zeroLedgerWrites() {
        UUID deviceId = insertDevice();
        UUID batchId  = insertPendingBatch(deviceId);

        long entriesBefore = countRows("ledger_entries");
        long txnsBefore    = countRows("ledger_transactions");

        poller.processOneRow();

        assertThat(countRows("ledger_entries"))
                .as("ledger_entries must not change for a malformed batch (§7 rule 5)")
                .isEqualTo(entriesBefore);
        assertThat(countRows("ledger_transactions"))
                .as("ledger_transactions must not change for a malformed batch (§7 rule 5)")
                .isEqualTo(txnsBefore);

        cleanup(batchId, deviceId);
    }

    // -------------------------------------------------------------------------
    // Setup helpers
    // -------------------------------------------------------------------------

    private UUID insertDevice() {
        UUID userId   = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        // Unique phone so multiple insertDevice() calls don't collide on the UNIQUE constraint.
        String phone = "+62900" + System.nanoTime() % 100_000_000L;
        // public_key is UNIQUE (V2 migration); generate a distinct value per device.
        String publicKey = "pk-worker-" + deviceId.toString().substring(0, 8);
        // device_token_hash is VARCHAR(64) NOT NULL UNIQUE (V2 migration).
        String tokenHash = UUID.randomUUID().toString().replace("-", "")
                         + UUID.randomUUID().toString().replace("-", "");
        jdbc.update(
                "INSERT INTO users (user_id, full_name, phone) VALUES (?, 'Worker Test User', ?)",
                userId, phone);
        jdbc.update(
                "INSERT INTO devices (device_id, user_id, public_key, device_label, device_token_hash) " +
                "VALUES (?, ?, ?, 'Worker Test Device', ?)",
                deviceId, userId, publicKey, tokenHash);
        return deviceId;
    }

    private UUID insertPendingBatch(UUID deviceId) {
        UUID batchId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO sync_inbox (batch_id, device_id, raw_payload, status) " +
                "VALUES (?, ?, '[]'::jsonb, 'PENDING')",
                batchId, deviceId);
        return batchId;
    }

    /** Deletes all sync_inbox rows (and dependent flagged_transactions) for a device, then the device and its user. */
    private void cleanupDevice(UUID deviceId) {
        List<UUID> userIds = jdbc.queryForList(
                "SELECT user_id FROM devices WHERE device_id = ?", UUID.class, deviceId);
        // FK order: flagged_transactions → sync_inbox → devices → users
        jdbc.update("DELETE FROM flagged_transactions WHERE batch_id IN " +
                    "(SELECT batch_id FROM sync_inbox WHERE device_id = ?)", deviceId);
        jdbc.update("DELETE FROM sync_inbox WHERE device_id = ?", deviceId);
        jdbc.update("DELETE FROM devices WHERE device_id = ?", deviceId);
        userIds.forEach(uid -> jdbc.update("DELETE FROM users WHERE user_id = ?", uid));
    }

    private void cleanup(UUID batchId, UUID deviceId) {
        List<UUID> userIds = jdbc.queryForList(
                "SELECT user_id FROM devices WHERE device_id = ?", UUID.class, deviceId);
        // FK order: flagged_transactions → sync_inbox → devices → users
        jdbc.update("DELETE FROM flagged_transactions WHERE batch_id = ?", batchId);
        jdbc.update("DELETE FROM sync_inbox WHERE batch_id = ?", batchId);
        jdbc.update("DELETE FROM devices WHERE device_id = ?", deviceId);
        userIds.forEach(uid -> jdbc.update("DELETE FROM users WHERE user_id = ?", uid));
    }

    private long countRows(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }
}
