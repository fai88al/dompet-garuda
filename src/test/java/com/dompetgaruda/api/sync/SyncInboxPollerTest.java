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
 * Integration tests for the worker inbox poller (PR7 stub).
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
 *   <li>PENDING row → PROCESSING → PENDING stub flow (no ledger writes, §7 rule 5).</li>
 *   <li>Empty inbox → returns {@code false}, no exception.</li>
 *   <li>Multiple PENDING rows → {@code processOneRow()} drains them one at a time.</li>
 *   <li>ShedLock row written to {@code shedlock} table after {@code pollInbox()} runs (§7
 *       invariant 7).</li>
 *   <li>Zero ledger writes — §7 rule 5: only PR8 settlement may post to the ledger.</li>
 * </ol>
 *
 * <p>Poison-pill (exception → FAILED) path is deferred to PR8, where real signature
 * verification introduces natural failure modes that can be meaningfully exercised.
 */
class SyncInboxPollerTest extends WorkerIntegrationTestBase {

    @Autowired SyncInboxPoller poller;
    @Autowired JdbcTemplate    jdbc;

    // -------------------------------------------------------------------------
    // Stub flow: PENDING → PROCESSING → PENDING
    // -------------------------------------------------------------------------

    @Test
    void processOneRow_pendingRow_setsStatusBackToPending() {
        UUID deviceId = insertDevice();
        UUID batchId  = insertPendingBatch(deviceId);

        boolean found = poller.processOneRow();

        assertThat(found).isTrue();
        String status = jdbc.queryForObject(
                "SELECT status FROM sync_inbox WHERE batch_id = ?", String.class, batchId);
        assertThat(status).isEqualTo("PENDING");

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
    void processOneRow_multiplePendingRows_processesEachInTurn() {
        UUID deviceId = insertDevice();
        UUID batchId1 = insertPendingBatch(deviceId);
        UUID batchId2 = insertPendingBatch(deviceId);

        assertThat(poller.processOneRow()).isTrue();  // first row
        assertThat(poller.processOneRow()).isTrue();  // second row
        assertThat(poller.processOneRow()).isFalse(); // inbox empty

        // Both rows are back to PENDING (stub resets status)
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sync_inbox WHERE device_id = ? AND status = 'PENDING'",
                Integer.class, deviceId);
        assertThat(pending).isEqualTo(2);

        cleanup(batchId1, deviceId);
        cleanup(batchId2, deviceId);
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
    void processOneRow_pendingRow_zeroLedgerWrites() {
        UUID deviceId = insertDevice();
        UUID batchId  = insertPendingBatch(deviceId);

        long entriesBefore = countRows("ledger_entries");
        long txnsBefore    = countRows("ledger_transactions");

        poller.processOneRow();

        assertThat(countRows("ledger_entries"))
                .as("ledger_entries must not change during stub polling (§7 rule 5)")
                .isEqualTo(entriesBefore);
        assertThat(countRows("ledger_transactions"))
                .as("ledger_transactions must not change during stub polling (§7 rule 5)")
                .isEqualTo(txnsBefore);

        cleanup(batchId, deviceId);
    }

    // -------------------------------------------------------------------------
    // Setup helpers
    // -------------------------------------------------------------------------

    private UUID insertDevice() {
        UUID userId   = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (user_id, full_name, phone) VALUES (?, 'Worker Test User', ?)",
                userId, "+62900" + System.nanoTime() % 100_000_000L);
        jdbc.update(
                "INSERT INTO devices (device_id, user_id, public_key, device_label) " +
                "VALUES (?, ?, 'pk-worker-test', 'Worker Test Device')",
                deviceId, userId);
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

    private void cleanup(UUID batchId, UUID deviceId) {
        // Resolve FK before deleting — device row must still exist for the userId lookup.
        List<UUID> userIds = jdbc.queryForList(
                "SELECT user_id FROM devices WHERE device_id = ?", UUID.class, deviceId);
        jdbc.update("DELETE FROM sync_inbox WHERE batch_id = ?", batchId);
        jdbc.update("DELETE FROM devices WHERE device_id = ?", deviceId);
        userIds.forEach(uid -> jdbc.update("DELETE FROM users WHERE user_id = ?", uid));
    }

    private long countRows(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }
}
