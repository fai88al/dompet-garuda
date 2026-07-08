package com.dompetgaruda.api.sync.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Acknowledges receipt of an uploaded sync batch (FR5).
 *
 * <p>The {@code batchId} is the primary key of the newly created {@code sync_inbox} row.
 * The device can use it to correlate the {@code sync-result} MQTT message published
 * by the worker after settlement.
 */
@Schema(description = "Acknowledgement returned immediately after the batch is stored.")
public record SyncBatchResponse(

        @Schema(description = "UUID assigned to this batch in sync_inbox. " +
                              "Appears in the MQTT sync-result notification after worker settlement.",
                example = "c3d4e5f6-a7b8-9012-cdef-123456789012")
        UUID batchId,

        @Schema(description = "Initial status — always PENDING at ingest time.", example = "PENDING")
        String status
) {}
