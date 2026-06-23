package com.dompetgaruda.api.ledger;

import java.util.UUID;

/**
 * One side of a double-entry posting.
 *
 * <p>Invariants (CLAUDE.md §7):
 * <ul>
 *   <li>{@code amount} is in whole Rupiah (IDR), always positive. The {@code direction} field
 *       carries the sign — never use negative amounts.</li>
 *   <li>{@code direction} is exactly {@code "DEBIT"} or {@code "CREDIT"}.</li>
 * </ul>
 */
public record LedgerEntry(UUID accountId, String direction, long amount) {

    public LedgerEntry {
        if (!"DEBIT".equals(direction) && !"CREDIT".equals(direction))
            throw new IllegalArgumentException("direction must be DEBIT or CREDIT, got: " + direction);
        if (amount <= 0)
            throw new IllegalArgumentException("amount must be positive (whole Rupiah), got: " + amount);
    }
}
