package com.dompetgaruda.api.sync.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Signed batch of offline transactions uploaded by the device at sync time (FR5).
 *
 * <p>The API stores this payload verbatim in {@code sync_inbox.raw_payload} and returns 202.
 * No signature verification or ledger writes happen here — that is the worker's job.
 */
@Schema(description = "Signed batch of offline transactions uploaded by the device at reconnect.")
public record SyncBatchRequest(

        @NotNull
        @Schema(description = "UUID of the offline certificate that authorised the offline spend.",
                example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
        UUID certificateId,

        @NotNull
        @Schema(description = "List of offline BLE transfers in this batch (at least one expected).")
        List<SyncOfflineTxnRequest> transactions
) {}
