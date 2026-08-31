package com.dompetgaruda.api.device.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Response after updating a device's status.")
public record UpdateDeviceStatusResponse(
        @Schema(description = "Device identifier — plain string, not a UUID (CLAUDE.md §1a).",
                example = "A1B2C3D4E5F6")
        String deviceId,
        @Schema(description = "The new device status: ACTIVE, SUSPENDED, or LOCKED.")
        String status,
        @Schema(description = "UTC timestamp of the last status update.")
        Instant updatedAt
) {}
