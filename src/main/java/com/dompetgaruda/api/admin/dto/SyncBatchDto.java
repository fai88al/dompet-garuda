package com.dompetgaruda.api.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Sync inbox batch entry. raw_payload is intentionally excluded — it may be large and contains device data.")
public record SyncBatchDto(
        @Schema(description = "Batch identifier.") UUID batchId,
        @Schema(description = "Device that uploaded this batch (plain string, not a UUID — CLAUDE.md §1a).",
                example = "A1B2C3D4E5F6") String deviceId,
        @Schema(description = "Processing status: PENDING, PROCESSING, DONE, or FAILED.") String status,
        @Schema(description = "True if the device synced after the offline certificate expired.") boolean syncedAfterExpiry,
        @Schema(description = "When the batch was received by the server.") Instant receivedAt,
        @Schema(description = "When the worker finished processing this batch (null if not yet processed).") Instant processedAt,
        @Schema(description = "Error detail if processing failed (null otherwise).") String errorReason
) {}
