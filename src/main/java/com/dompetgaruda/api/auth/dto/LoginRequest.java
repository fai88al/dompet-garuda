package com.dompetgaruda.api.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Admin login request. The password is compared against ADMIN_API_TOKEN.")
public record LoginRequest(
        @NotBlank
        @Schema(description = "Admin password. Never logged server-side.")
        String password
) {}
