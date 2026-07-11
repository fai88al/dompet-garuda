package com.dompetgaruda.api.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Anomaly flagged during settlement or reconciliation.")
public record FlaggedTransactionDto(
        @Schema(description = "Flag identifier.") long flagId,
        @Schema(description = "Reason code: OVER_LIMIT, BAD_SIGNATURE, COUNTER_REPLAY, EXPIRED_CERT_LATE_SYNC, RECON_MISMATCH, or MALFORMED.") String reason,
        @Schema(description = "Human-readable detail about the anomaly.") String detail,
        @Schema(description = "When the flag was created.") Instant createdAt,
        @Schema(description = "Related offline transaction ID, or null.") UUID offlineTxnId,
        @Schema(description = "Related sync batch ID, or null.") UUID batchId,
        @Schema(description = "Related offline certificate ID, or null.") UUID certificateId
) {}
