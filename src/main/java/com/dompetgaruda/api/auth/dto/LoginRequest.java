package com.dompetgaruda.api.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Admin login request. Username is an email address (CLAUDE.md §4).")
public record LoginRequest(
        @NotBlank
        @Schema(description = "Email address of the admin/writer account.")
        String username,

        @NotBlank
        @Schema(description = "Account password. Never logged server-side (§7.9).")
        String password
) {}
