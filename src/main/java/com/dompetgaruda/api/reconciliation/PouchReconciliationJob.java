package com.dompetgaruda.api.reconciliation;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hourly worker job that audits every ACTIVE and SETTLED offline certificate
 * and flags any whose ledger arithmetic does not reconcile (FR9, §7.11).
 *
 * <h2>Reconciliation arithmetic per certificate</h2>
 * <pre>
 *   settled_outflows  = SUM of OFFLINE_TRANSFER DEBIT entries for pouch_account_id
 *   refund_amount     = SUM of POUCH_REFUND DEBIT entries for pouch_account_id
 *   expected_remaining = issued_amount - settled_outflows - refund_amount
 *   actual_remaining   = SUM(CREDIT) - SUM(DEBIT) for pouch_account_id (current ledger balance)
 * </pre>
 *
 * <p>If expected_remaining ≠ actual_remaining and no unresolved RECON_MISMATCH flag already
 * exists for the certificate, a new flag is written to {@code flagged_transactions}.
 * The ledger is never modified — this job is read-only on ledger tables (§7.1).
 *
 * <p>{@code @Profile("worker")} — never loaded in the api container (§3 profile isolation rule).
 * {@code @SchedulerLock} — §7 invariant 7: all @Scheduled methods must have ShedLock.
 */
@Component
@Profile("worker")
public class PouchReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(PouchReconciliationJob.class);

    private final JdbcTemplate jdbc;

    public PouchReconciliationJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelay = 3_600_000)
    @SchedulerLock(name = "reconciliation-job", lockAtMostFor = "PT55M", lockAtLeastFor = "PT30S")
    public void reconcile() {
        doReconcile();
    }

    /**
     * Reconciliation logic, separated from the scheduling/locking entry point so tests can
     * call it directly without depending on ShedLock behaviour (mirrors the
     * {@code SyncInboxPoller.processOneRow()} pattern).
     */
    void doReconcile() {
        List<Map<String, Object>> certs = jdbc.queryForList(
                "SELECT certificate_id, pouch_account_id, issued_amount " +
                "FROM offline_certificates " +
                "WHERE status IN ('ACTIVE', 'SETTLED')");

        int checked = 0;
        int mismatches = 0;

        for (Map<String, Object> cert : certs) {
            UUID certId         = (UUID)   cert.get("certificate_id");
            UUID pouchAccountId = (UUID)   cert.get("pouch_account_id");
            long issuedAmount   = ((Number) cert.get("issued_amount")).longValue();

            long settledOutflows   = sumDebitByType(pouchAccountId, "OFFLINE_TRANSFER");
            long refundAmount      = sumDebitByType(pouchAccountId, "POUCH_REFUND");
            long expectedRemaining = issuedAmount - settledOutflows - refundAmount;
            long actualRemaining   = currentBalance(pouchAccountId);

            checked++;

            if (expectedRemaining != actualRemaining && !hasUnresolvedFlag(certId)) {
                insertFlag(certId, expectedRemaining, actualRemaining);
                mismatches++;
            }
        }

        log.info("Reconciliation complete. Checked: {} certificates. Mismatches: {}", checked, mismatches);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Sums the DEBIT amounts on {@code pouchAccountId} across all ledger entries
     * belonging to transactions of the given type.
     */
    private long sumDebitByType(UUID pouchAccountId, String txnType) {
        Long total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(le.amount), 0) " +
                "FROM ledger_entries le " +
                "JOIN ledger_transactions lt ON le.transaction_id = lt.transaction_id " +
                "WHERE lt.type = ? " +
                "  AND le.direction = 'DEBIT' " +
                "  AND le.account_id = ?",
                Long.class, txnType, pouchAccountId);
        return total == null ? 0L : total;
    }

    /** Current ledger balance: SUM(CREDIT) − SUM(DEBIT) for the account. */
    private long currentBalance(UUID accountId) {
        Long balance = jdbc.queryForObject(
                "SELECT COALESCE(" +
                "  SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END), 0" +
                ") FROM ledger_entries WHERE account_id = ?",
                Long.class, accountId);
        return balance == null ? 0L : balance;
    }

    /**
     * Returns true if an unresolved RECON_MISMATCH flag already exists for this certificate.
     * Used to make flagging idempotent — skip if mismatch was already flagged.
     */
    private boolean hasUnresolvedFlag(UUID certId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flagged_transactions " +
                "WHERE certificate_id = ? AND reason = 'RECON_MISMATCH' AND resolved = false",
                Integer.class, certId);
        return count != null && count > 0;
    }

    private void insertFlag(UUID certId, long expected, long actual) {
        jdbc.update(
                "INSERT INTO flagged_transactions (certificate_id, reason, detail) " +
                "VALUES (?, 'RECON_MISMATCH', ?)",
                certId,
                "expected=" + expected + " actual=" + actual);
    }
}
