package com.dompetgaruda.api.wallet.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Request to load funds into the device's offline pouch.")
public record PouchLoadRequest(

        @NotNull
        @Min(value = 1, message = "Amount must be at least 1 Rupiah")
        @Schema(description = "Amount to load in whole Rupiah (IDR). Must be > 0, ≤ pouch max, ≤ online balance.", example = "100000")
        Long amount
) {}
