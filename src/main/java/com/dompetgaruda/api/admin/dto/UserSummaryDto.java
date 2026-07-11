package com.dompetgaruda.api.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Summary of a user including their derived online balance and device count.")
public record UserSummaryDto(
        @Schema(description = "Unique user identifier.") UUID userId,
        @Schema(description = "User's full name.") String fullName,
        @Schema(description = "User's phone number.") String phone,
        @Schema(description = "Account status: ACTIVE or SUSPENDED.") String status,
        @Schema(description = "Online balance in whole Rupiah, derived from ledger (SUM CREDIT − SUM DEBIT).") long onlineBalance,
        @Schema(description = "Number of devices registered to this user.") long deviceCount,
        @Schema(description = "When the user account was created.") Instant createdAt
) {}
