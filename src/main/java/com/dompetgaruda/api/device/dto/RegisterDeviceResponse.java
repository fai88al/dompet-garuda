package com.dompetgaruda.api.device.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Registered device details. The deviceToken is shown exactly once — store it securely before discarding this response.")
public record RegisterDeviceResponse(
        @Schema(description = "Unique device identifier (UUID).")
        UUID deviceId,

        @Schema(description = "UUID of the owning user.")
        UUID userId,

        @Schema(description = "Human-readable device label.")
        String deviceLabel,

        @Schema(description = "UUID of the device's POUCH ledger account — used for offline balance tracking.")
        UUID pouchAccountId,

        @Schema(description = "UTC timestamp of device registration.")
        Instant registeredAt,

        @Schema(description = "Plaintext Bearer token the device uses to authenticate API calls. Shown ONCE — cannot be recovered. Provision this onto the device immediately.")
        String deviceToken
) {}
