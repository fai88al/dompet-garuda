package com.dompetgaruda.api.device.dto;

import java.time.Instant;
import java.util.UUID;

public record CreateUserResponse(
        UUID userId,
        String fullName,
        String phone,
        String status,
        UUID onlineAccountId,
        Instant createdAt
) {}
