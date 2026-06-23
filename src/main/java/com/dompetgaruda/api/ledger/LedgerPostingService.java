package com.dompetgaruda.api.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Double-entry ledger engine. All money writes use plain SQL via {@link JdbcTemplate}
 * so every statement is visible and reviewable (CLAUDE.md §2).
 *
 * <h2>Invariants (CLAUDE.md §7)</h2>
 * <ol>
 *   <li>Every posting is <em>balanced</em>: SUM(CREDIT amounts) == SUM(DEBIT amounts).
 *       Unbalanced calls throw {@link UnbalancedPostingException}; the entire DB
 *       transaction rolls back and no rows are written.</li>
 *   <li>Money is {@code long} — whole Rupiah (IDR). No {@code float} or {@code double}
 *       anywhere in the money path.</li>
 *   <li>{@code ledger_entries} rows are append-only; they are never updated or deleted.</li>
 *   <li>Both the {@code ledger_transactions} insert and all {@code ledger_entries} inserts
 *       happen in a single DB transaction — either all commit or none do.</li>
 *   <li>Balance is always derived as SUM(CREDIT) − SUM(DEBIT) from {@code ledger_entries};
 *       there is no mutable balance column (CLAUDE.md §7 rule 1).</li>
 * </ol>
 *
 * <h2>Posting reference (CLAUDE.md §3)</h2>
 * <pre>
 *   TOPUP            : DEBIT system        → CREDIT user.online
 *   POUCH_LOAD       : DEBIT user.online   → CREDIT device.pouch
 *   OFFLINE_TRANSFER : DEBIT sender.pouch  → CREDIT receiver.online
 *   POUCH_REFUND     : DEBIT device.pouch  → CREDIT user.online
 * </pre>
 */
@Service
public class LedgerPostingService {

    /**
     * Fixed account_id of the SYSTEM funding account, seeded in V1__init.sql.
     * The system account is the source of all top-up credits.
     */
    public static final UUID SYSTEM_ACCOUNT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final JdbcTemplate jdbc;

    public LedgerPostingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Post a balanced set of ledger entries in a single DB transaction.
     *
     * <p>Rejects the entire posting (throws before any insert) if credits ≠ debits.
     *
     * @param req the posting request — must have at least one DEBIT and one CREDIT entry
     * @return the generated {@code ledger_transactions.transaction_id}
     * @throws UnbalancedPostingException if total credits ≠ total debits
     */
    @Transactional
    public long post(PostingRequest req) {
        assertBalanced(req.entries());

        Long txnId = jdbc.queryForObject(
                "INSERT INTO ledger_transactions (type, reference_type, reference_id, description) " +
                "VALUES (?, ?, ?, ?) RETURNING transaction_id",
                Long.class,
                req.type(), req.referenceType(), req.referenceId(), req.description());

        for (LedgerEntry e : req.entries()) {
            jdbc.update(
                    "INSERT INTO ledger_entries " +
                    "(transaction_id, account_id, direction, amount, currency) " +
                    "VALUES (?, ?, ?, ?, 'IDR')",
                    txnId, e.accountId(), e.direction(), e.amount());
        }
        return txnId;
    }

    /**
     * Derive the current balance of an account from the ledger.
     *
     * <p>Balance = SUM(CREDIT amounts) − SUM(DEBIT amounts) across all entries for
     * the account. Returns {@code 0} for an account with no entries. This method is a
     * read — it makes no ledger writes (CLAUDE.md §7 rule 1 / FR14 invariant).
     *
     * @param accountId the ledger account to query
     * @return balance in whole Rupiah; may be negative for the SYSTEM account
     */
    public long getBalance(UUID accountId) {
        Long balance = jdbc.queryForObject(
                "SELECT COALESCE(" +
                "  SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END), 0" +
                ") FROM ledger_entries WHERE account_id = ?",
                Long.class,
                accountId);
        return balance == null ? 0L : balance;
    }

    /**
     * Returns the fixed SYSTEM funding account id seeded in V1__init.sql.
     * The SYSTEM account is debited on every top-up.
     */
    public UUID resolveSystemAccount() {
        return SYSTEM_ACCOUNT_ID;
    }

    /**
     * Resolves the ONLINE ledger account for the given user.
     * Throws {@link org.springframework.dao.EmptyResultDataAccessException} if none found.
     */
    public UUID resolveOnlineAccount(UUID userId) {
        return jdbc.queryForObject(
                "SELECT account_id FROM accounts WHERE user_id = ? AND type = 'ONLINE'",
                UUID.class,
                userId);
    }

    /**
     * Resolves the POUCH ledger account for the given device.
     * Throws {@link org.springframework.dao.EmptyResultDataAccessException} if none found.
     */
    public UUID resolvePouchAccount(UUID deviceId) {
        return jdbc.queryForObject(
                "SELECT account_id FROM accounts WHERE device_id = ? AND type = 'POUCH'",
                UUID.class,
                deviceId);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void assertBalanced(List<LedgerEntry> entries) {
        long totalCredit = entries.stream()
                .filter(e -> "CREDIT".equals(e.direction()))
                .mapToLong(LedgerEntry::amount)
                .sum();
        long totalDebit = entries.stream()
                .filter(e -> "DEBIT".equals(e.direction()))
                .mapToLong(LedgerEntry::amount)
                .sum();
        if (totalCredit != totalDebit) {
            throw new UnbalancedPostingException(
                    "Posting not balanced: totalCredit=" + totalCredit +
                    " totalDebit=" + totalDebit);
        }
    }
}
