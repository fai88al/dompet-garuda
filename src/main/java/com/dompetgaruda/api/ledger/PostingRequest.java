package com.dompetgaruda.api.ledger;

import java.util.List;

/**
 * Groups the entries that form one balanced ledger transaction.
 *
 * <p>{@code type} must be one of the values permitted by the {@code ledger_transactions.type}
 * check constraint: {@code TOPUP}, {@code POUCH_LOAD}, {@code OFFLINE_TRANSFER},
 * {@code POUCH_REFUND}. See CLAUDE.md §3 posting reference.
 *
 * <p>{@code referenceType} and {@code referenceId} are optional audit links to the domain
 * entity that caused this posting (e.g. a sync batch id or certificate id).
 */
public record PostingRequest(
        String type,
        String referenceType,
        String referenceId,
        String description,
        List<LedgerEntry> entries
) {}
