package com.dompetgaruda.api.device.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Response after updating a device's status.")
public record UpdateDeviceStatusResponse(
        @Schema(description = "Device UUID.")
        UUID deviceId,
        @Schema(description = "The new device status: ACTIVE, SUSPENDED, or LOCKED.")
        String status,
        @Schema(description = "UTC timestamp of the last status update.")
        Instant updatedAt
) {}
