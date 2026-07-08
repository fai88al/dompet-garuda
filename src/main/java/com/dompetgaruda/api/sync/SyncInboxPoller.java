package com.dompetgaruda.api.sync;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Worker-profile inbox poller (PR7 — stub; full settlement in PR8).
 *
 * <p>Every 5 seconds, drains all PENDING rows from {@code sync_inbox} one at a time using
 * {@code SELECT … FOR UPDATE SKIP LOCKED} so concurrent worker instances don't collide.
 *
 * <p>Per row:
 * <ol>
 *   <li>Claim the row: set {@code status = 'PROCESSING'}.</li>
 *   <li>Log the {@code batch_id} at INFO level. Raw payload is never logged (§7 rule 9).</li>
 *   <li>Stub reset: set {@code status = 'PENDING'} back. PR8 will replace this with
 *       Ed25519 signature verification and ledger posting.</li>
 *   <li>On any exception: set {@code status = 'FAILED'} with {@code error_reason}
 *       — never silently drop a row (§7 invariant 11).</li>
 * </ol>
 *
 * <p>{@code @Profile("worker")} required — the api container must never run scheduled jobs.
 * {@code @SchedulerLock} required — §7 invariant 7: all scheduled jobs must have ShedLock.
 */
@Component
@Profile("worker")
public class SyncInboxPoller {

    private static final Logger log = LoggerFactory.getLogger(SyncInboxPoller.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public SyncInboxPoller(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx   = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelay = 5_000)
    @SchedulerLock(name = "sync-inbox-poller", lockAtMostFor = "PT30S", lockAtLeastFor = "PT5S")
    public void pollInbox() {
        while (processOneRow()) {
            // keep draining until inbox is empty
        }
    }

    /**
     * Claims and processes one PENDING row. Returns {@code true} if a row was found
     * (caller should poll again), {@code false} when the inbox is empty.
     *
     * <p>Each row is processed inside its own transaction so a failure on one row does
     * not roll back the commit on previous rows.
     */
    boolean processOneRow() {
        Boolean found = tx.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT batch_id FROM sync_inbox " +
                    "WHERE status = 'PENDING' " +
                    "ORDER BY received_at " +
                    "FOR UPDATE SKIP LOCKED " +
                    "LIMIT 1");

            if (rows.isEmpty()) {
                return false;
            }

            UUID batchId = (UUID) rows.get(0).get("batch_id");

            jdbc.update(
                    "UPDATE sync_inbox SET status = 'PROCESSING' WHERE batch_id = ?",
                    batchId);

            try {
                log.info("Processing sync batch: {}", batchId);

                // TODO PR8: validate Ed25519 signatures and post ledger entries
                jdbc.update(
                        "UPDATE sync_inbox SET status = 'PENDING' WHERE batch_id = ?",
                        batchId);

            } catch (Exception e) {
                log.error("Failed to process sync batch {}: {}", batchId, e.getMessage());
                jdbc.update(
                        "UPDATE sync_inbox SET status = 'FAILED', error_reason = ? " +
                        "WHERE batch_id = ?",
                        e.getMessage(), batchId);
            }

            return true;
        });

        return Boolean.TRUE.equals(found);
    }
}
