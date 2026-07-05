package com.dompetgaruda.api.wallet.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Offline certificate issued after a successful pouch load.")
public record PouchLoadResponse(

        @Schema(description = "Unique certificate identifier (UUID).")
        UUID certificateId,

        @Schema(description = "Amount loaded into the pouch in whole Rupiah. Also the maximum the device may spend offline.", example = "100000")
        long issuedAmount,

        @Schema(description = "UTC timestamp when this certificate expires (issued_at + 24h).")
        Instant expiresAt,

        @Schema(description = "Base64-encoded Ed25519 server signature over 'certificateId|deviceId|issuedAmount|expiresAtEpochSeconds'. The device uses this to prove the server authorised the pouch.")
        String serverSignature
) {}
