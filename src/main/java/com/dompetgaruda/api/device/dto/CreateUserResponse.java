package com.dompetgaruda.api.device.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Created user with their online ledger account identifier.")
public record CreateUserResponse(
        @Schema(description = "Unique user identifier (UUID).")
        UUID userId,

        @Schema(description = "Full name as provided at registration.")
        String fullName,

        @Schema(description = "Phone number as provided at registration.")
        String phone,

        @Schema(description = "Account status — always ACTIVE on creation.", example = "ACTIVE")
        String status,

        @Schema(description = "UUID of the user's ONLINE ledger account — used internally for balance derivation.")
        UUID onlineAccountId,

        @Schema(description = "UTC timestamp of user creation.")
        Instant createdAt
) {}
