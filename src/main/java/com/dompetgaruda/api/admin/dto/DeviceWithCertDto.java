package com.dompetgaruda.api.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Device with its active certificate (if any).")
public record DeviceWithCertDto(
        @Schema(description = "Unique device identifier.") UUID deviceId,
        @Schema(description = "Owner user identifier.") UUID userId,
        @Schema(description = "Owner user's phone number.") String userPhone,
        @Schema(description = "Device status: ACTIVE, SUSPENDED, or LOCKED.") String status,
        @Schema(description = "Highest settled sender counter (replay guard).") long lastCounter,
        @Schema(description = "When the device was registered.") Instant registeredAt,
        @Schema(description = "Active offline certificate, or null if none is currently active.") ActiveCertDto activeCertificate
) {}
