package com.dompetgaruda.api.qrpayment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "A newly created PENDING Bayar QR Online payment request.")
public record CreatePaymentRequestResponse(
        @Schema(description = "Unique payment request id.") UUID requestId,
        @Schema(description = "Amount the payer must pay, in whole Rupiah.", example = "75000") long amount,
        @Schema(description = "Random nonce embedded in the QR payload; not the requestId.") String nonce,
        @Schema(description = "UTC timestamp when this request expires.") Instant expiresAt,
        @Schema(description = "String the device encodes into a QR image: '{requestId}|{receiverUserId}|{amount}|{nonce}'.") String qrPayload
) {}
