package com.dompetgaruda.api.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Device summary as nested in a user detail response.")
public record DeviceSummaryDto(
        @Schema(description = "Unique device identifier.") UUID deviceId,
        @Schema(description = "Device status: ACTIVE, SUSPENDED, or LOCKED.") String status,
        @Schema(description = "When the device was registered.") Instant registeredAt
) {}
