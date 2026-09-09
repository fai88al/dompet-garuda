package com.dompetgaruda.api.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Distinct users with a settled transaction in each rolling window, anchored to now.")
public record ActiveUsersDto(
        @Schema(description = "Distinct active users in the last 24 hours.") long daily,
        @Schema(description = "Distinct active users in the last 7 days.") long sevenDay,
        @Schema(description = "Distinct active users in the last 30 days.") long thirtyDay
) {}
