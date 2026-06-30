package com.dompetgaruda.api.wallet.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Result of a top-up operation. onlineBalance is derived from the ledger — it is the authoritative figure, not a cached value.")
public record TopUpResponse(

        @Schema(description = "UUID of the user whose online balance was credited.")
        UUID userId,

        @Schema(description = "Online balance after this posting, in whole Rupiah. Derived from SUM(CREDIT) − SUM(DEBIT) across all ledger entries for the user's ONLINE account.", example = "50000")
        long onlineBalance,

        @Schema(description = "Internal ledger_transactions.transaction_id of the posted entry — useful for audit.", example = "42")
        long transactionId,

        @Schema(description = "The reference string submitted in the request.", example = "manual-topup-001")
        String reference
) {}
