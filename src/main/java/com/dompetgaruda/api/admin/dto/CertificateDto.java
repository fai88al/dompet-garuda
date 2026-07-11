package com.dompetgaruda.api.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Offline certificate record.")
public record CertificateDto(
        @Schema(description = "Certificate identifier.") UUID certificateId,
        @Schema(description = "Device this certificate was issued to.") UUID deviceId,
        @Schema(description = "Phone number of the device owner.") String userPhone,
        @Schema(description = "Amount issued in whole Rupiah.") long issuedAmount,
        @Schema(description = "Certificate status: ACTIVE, SETTLED, EXPIRED, or REVOKED.") String status,
        @Schema(description = "When the certificate was issued.") Instant issuedAt,
        @Schema(description = "When the certificate expires.") Instant expiresAt,
        @Schema(description = "When the certificate was settled (null if not yet settled).") Instant settledAt
) {}
