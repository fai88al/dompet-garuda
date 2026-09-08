package com.dompetgaruda.api.transaction.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * One row of transaction history (CLAUDE.md §18, PRD v1.1 §3.2).
 *
 * @param transactionId the {@code ledger_transactions.transaction_id} — {@code null} for
 *                       {@link TransactionStatus#PENDING} and {@link TransactionStatus#FAILED}
 *                       rows, since no ledger posting exists yet (or ever, for FAILED).
 */
@Schema(description = "One transaction history entry.")
public record TransactionHistoryItemDto(
        Long transactionId,
        String referenceId,
        String type,
        String direction,
        long amount,
        String counterparty,
        TransactionStatus status,
        String notes,
        Instant createdAt
) {}
