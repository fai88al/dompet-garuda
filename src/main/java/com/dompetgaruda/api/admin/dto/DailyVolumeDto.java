package com.dompetgaruda.api.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Settled transaction count and amount for one (date, type) bucket.")
public record DailyVolumeDto(
        @Schema(description = "Bucket date, ISO-8601 (yyyy-MM-dd).") String date,
        @Schema(description = "Ledger transaction type, e.g. ONLINE_TRANSFER.") String type,
        @Schema(description = "Number of settled transactions of this type on this date.") long count,
        @Schema(description = "Total amount (whole Rupiah) across those transactions.") long totalAmount
) {}
