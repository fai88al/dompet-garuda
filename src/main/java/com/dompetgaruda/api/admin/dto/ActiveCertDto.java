package com.dompetgaruda.api.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Active offline certificate held by a device.")
public record ActiveCertDto(
        @Schema(description = "Certificate identifier.") UUID certificateId,
        @Schema(description = "Amount issued in whole Rupiah.") long issuedAmount,
        @Schema(description = "When the certificate expires.") Instant expiresAt,
        @Schema(description = "Certificate status (ACTIVE).") String status
) {}
