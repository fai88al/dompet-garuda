package com.dompetgaruda.api.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of a successful (or replayed) online transfer.")
public record TransferResponse(

        @Schema(description = "The ledger_transactions.transaction_id of the ONLINE_TRANSFER posting.", example = "123")
        long transactionId,

        @Schema(description = "Sender's online balance after the transfer, derived from the ledger.", example = "450000")
        long senderNewBalance
) {}
