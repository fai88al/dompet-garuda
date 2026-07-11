package com.dompetgaruda.api.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Full detail of a single user including their derived online balance and device list.")
public record UserDetailDto(
        @Schema(description = "Unique user identifier.") UUID userId,
        @Schema(description = "User's full name.") String fullName,
        @Schema(description = "User's phone number.") String phone,
        @Schema(description = "Account status: ACTIVE or SUSPENDED.") String status,
        @Schema(description = "Online balance in whole Rupiah, derived from ledger (SUM CREDIT − SUM DEBIT).") long onlineBalance,
        @Schema(description = "Number of devices registered to this user.") long deviceCount,
        @Schema(description = "When the user account was created.") Instant createdAt,
        @Schema(description = "Devices registered to this user.") List<DeviceSummaryDto> devices
) {}
