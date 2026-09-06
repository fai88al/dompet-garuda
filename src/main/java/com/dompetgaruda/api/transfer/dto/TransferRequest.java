package com.dompetgaruda.api.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for {@code POST /device/transfer}.
 *
 * <p>Deliberately carries no bean-validation annotations ({@code @NotNull}/{@code @Min}):
 * validation must run in the exact order specified by CLAUDE.md §14.1, and Spring's
 * {@code @Valid} would run before the idempotency replay check (step c), which must take
 * priority over every other check. {@link com.dompetgaruda.api.transfer.TransferService}
 * validates these fields manually, after the replay check.
 */
@Schema(description = "Request to transfer funds from the authenticated device's user to another registered device's user.")
public record TransferRequest(

        @Schema(description = "Recipient device id. Plain string, hardware-sourced (CLAUDE.md §1a) — not a UUID.", example = "AABBCCDDEEFF", requiredMode = Schema.RequiredMode.REQUIRED)
        String receiverDeviceId,

        @Schema(description = "Amount to transfer in whole Rupiah (IDR). Must be > 0 and <= transfer.online.max-amount-idr.", example = "50000", requiredMode = Schema.RequiredMode.REQUIRED)
        Long amount
) {}
