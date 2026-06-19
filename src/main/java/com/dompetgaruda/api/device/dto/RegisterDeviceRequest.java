package com.dompetgaruda.api.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record RegisterDeviceRequest(
        @NotNull
        UUID userId,

        @NotBlank
        String publicKey,

        @Size(max = 60)
        String label
) {}
