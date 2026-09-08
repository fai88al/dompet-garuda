package com.dompetgaruda.api.transaction.dto;

/**
 * Derived transaction status (CLAUDE.md §18) — never stored redundantly.
 *
 * <ul>
 *   <li>{@link #SUCCESS} — a settled {@code ledger_transactions} row exists.</li>
 *   <li>{@link #PENDING} — offline transaction uploaded but not yet worker-settled
 *       (still in {@code sync_inbox}).</li>
 *   <li>{@link #FAILED} — flagged, never posted ({@code flagged_transactions}).</li>
 *   <li>{@link #REVERSED} — reserved for a future reversal mechanism (CLAUDE.md §18
 *       Open Decision 2). Never produced by any code path in this milestone.</li>
 * </ul>
 */
public enum TransactionStatus {
    SUCCESS,
    PENDING,
    FAILED,
    REVERSED
}
