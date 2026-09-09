package com.dompetgaruda.api.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Settled transaction count for one ledger transaction type, within the requested range.")
public record TypeDistributionDto(
        @Schema(description = "Ledger transaction type, e.g. OFFLINE_TRANSFER.") String type,
        @Schema(description = "Number of settled transactions of this type.") long count
) {}
