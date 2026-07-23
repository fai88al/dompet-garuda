package com.dompetgaruda.api.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Successful admin login response. Use the returned token as a Bearer token on all /admin/** endpoints.")
public record LoginResponse(
        @Schema(description = "Signed JWT. Pass as 'Authorization: Bearer <token>' on admin requests.")
        String token,

        @Schema(description = "Token scheme. Always 'Bearer'.")
        String type,

        @Schema(description = "Email address of the authenticated account.")
        String username,

        @Schema(description = "Role of the authenticated account: ADMIN or WRITER.")
        String role
) {}
