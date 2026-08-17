package com.dompetgaruda.api.qrpayment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of a successful (or replayed) Bayar QR Online payment.")
public record PayPaymentRequestResponse(
        @Schema(description = "The ledger_transactions.transaction_id of the QR_PAYMENT_ONLINE posting.", example = "456")
        long transactionId,

        @Schema(description = "Payer's online balance after payment, derived from the ledger.", example = "375000")
        long payerNewBalance
) {}
