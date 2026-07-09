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
 * Worker-profile inbox poller. Drains {@code sync_inbox} PENDING rows one at a time,
 * delegating real settlement to {@link SyncSettlementService}.
 *
 * <p>Two-phase design keeps transaction scopes small:
 * <ol>
 *   <li><b>Claim phase</b> — brief TX: {@code SELECT … FOR UPDATE SKIP LOCKED} + set PROCESSING.
 *       Lock is held only for these two statements, then released on commit.</li>
 *   <li><b>Settle phase</b> — outside any outer TX: {@link SyncSettlementService#settle} manages
 *       its own per-transaction commits so earlier OFFLINE_TRANSFERs remain committed even if a
 *       later one fails.</li>
 * </ol>
 *
 * <p>On any unhandled exception escaping {@code settle()}, the batch is marked FAILED.
 * Malformed batches are handled inside {@code settle()} itself and return normally,
 * ensuring the poller always continues to the next batch (§7 invariant 11).
 *
 * <p>{@code @Profile("worker")} — the api container must never run scheduled jobs.
 * {@code @SchedulerLock} — §7 invariant 7: all scheduled jobs must have ShedLock.
 */
@Component
@Profile("worker")
public class SyncInboxPoller {

    private static final Logger log = LoggerFactory.getLogger(SyncInboxPoller.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SyncSettlementService settlementService;

    public SyncInboxPoller(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                            SyncSettlementService settlementService) {
        this.jdbc              = jdbc;
        this.tx                = new TransactionTemplate(txManager);
        this.settlementService = settlementService;
    }

    @Scheduled(fixedDelay = 5_000)
    @SchedulerLock(name = "sync-inbox-poller", lockAtMostFor = "PT30S", lockAtLeastFor = "PT5S")
    public void pollInbox() {
        while (processOneRow()) {
            // keep draining until inbox is empty
        }
    }

    /**
     * Claims and settles one PENDING row. Returns {@code true} if a row was found
     * (caller should poll again), {@code false} when the inbox is empty.
     */
    boolean processOneRow() {
        // Phase 1: atomically claim one PENDING row
        UUID batchId = tx.execute(txStatus -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT batch_id FROM sync_inbox " +
                    "WHERE status = 'PENDING' " +
                    "ORDER BY received_at " +
                    "FOR UPDATE SKIP LOCKED " +
                    "LIMIT 1");

            if (rows.isEmpty()) return null;

            UUID id = (UUID) rows.get(0).get("batch_id");
            jdbc.update("UPDATE sync_inbox SET status = 'PROCESSING' WHERE batch_id = ?", id);
            return id;
        });

        if (batchId == null) return false;

        // Phase 2: settle outside any outer TX — each OFFLINE_TRANSFER commits independently
        log.info("Processing sync batch: {}", batchId);
        try {
            settlementService.settle(batchId);
        } catch (Exception e) {
            log.error("Unexpected failure processing batch {}: {}", batchId, e.getMessage());
            jdbc.update(
                    "UPDATE sync_inbox SET status = 'FAILED', error_reason = ? WHERE batch_id = ?",
                    e.getMessage(), batchId);
        }

        return true;
    }
}
