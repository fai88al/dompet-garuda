package com.dompetgaruda.api.qrpayment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request to create a Bayar QR Online payment request.")
public record CreatePaymentRequestRequest(
        @Schema(description = "Amount the payer must pay, in whole Rupiah (IDR). Must be > 0.", example = "75000", requiredMode = Schema.RequiredMode.REQUIRED)
        Long amount
) {}
