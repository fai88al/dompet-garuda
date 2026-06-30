package com.dompetgaruda.api.wallet.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

@Schema(description = "Request body for topping up a user's online balance.")
public record TopUpRequest(

        @Schema(description = "Amount to credit in whole Rupiah (IDR). Must be > 0.", example = "50000")
        @Positive(message = "amount must be greater than 0")
        long amount,

        @Schema(description = "Caller-supplied reference string stored in the ledger for audit purposes.", example = "manual-topup-001")
        String reference
) {}
