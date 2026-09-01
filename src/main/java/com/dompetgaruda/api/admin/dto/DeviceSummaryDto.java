package com.dompetgaruda.api.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Device summary as nested in a user detail response.")
public record DeviceSummaryDto(
        @Schema(description = "Unique device identifier — plain string, not a UUID (CLAUDE.md §1a).",
                example = "A1B2C3D4E5F6") String deviceId,
        @Schema(description = "Device status: ACTIVE, SUSPENDED, or LOCKED.") String status,
        @Schema(description = "When the device was registered.") Instant registeredAt
) {}
