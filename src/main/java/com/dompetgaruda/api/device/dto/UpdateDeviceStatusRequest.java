package com.dompetgaruda.api.device.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request body for updating a device's status.")
public record UpdateDeviceStatusRequest(
        @NotBlank
        @Schema(description = "New device status. Must be one of: ACTIVE, SUSPENDED, LOCKED.",
                allowableValues = {"ACTIVE", "SUSPENDED", "LOCKED"})
        String status
) {}
