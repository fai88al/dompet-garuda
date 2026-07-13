package com.dompetgaruda.api.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Response after marking a flagged transaction as resolved.")
public record FlagResolveResponse(
        @Schema(description = "Primary key of the resolved flag.")
        long flagId,
        @Schema(description = "Always true in a success response.")
        boolean resolved,
        @Schema(description = "UTC timestamp when the flag was resolved.")
        Instant resolvedAt
) {}
