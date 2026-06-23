package com.dompetgaruda.api.device.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Schema(description = "Request body for registering an ESP32 device against an existing user.")
public record RegisterDeviceRequest(
        @Schema(description = "UUID of the user who owns this device.", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        @NotNull
        UUID userId,

        @Schema(description = "Base64-encoded Ed25519 public key from the device firmware. Used to verify offline transaction signatures.", example = "MCowBQYDK2VwAyEA...")
        @NotBlank
        String publicKey,

        @Schema(description = "Human-readable label for the device (optional, max 60 chars).", example = "Device 1", maxLength = 60)
        @Size(max = 60)
        String label
) {}
