package com.dompetgaruda.api.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Successful admin login response. Use the returned token as a Bearer token on all /admin/** endpoints.")
public record LoginResponse(
        @Schema(description = "The ADMIN_API_TOKEN value — use as Bearer token. Never logged server-side.")
        String token,
        @Schema(description = "Token scheme. Always 'Bearer'.")
        String type
) {}
