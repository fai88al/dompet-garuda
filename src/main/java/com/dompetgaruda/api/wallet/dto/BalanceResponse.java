package com.dompetgaruda.api.wallet.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Balance figures for the calling device's user. Both values are derived server-side — the server does not see unsynced offline spends.")
public record BalanceResponse(

        @Schema(description = "User's ONLINE ledger balance in whole Rupiah, derived as SUM(CREDIT) − SUM(DEBIT) over all ONLINE account entries.", example = "150000")
        long onlineBalance,

        @Schema(description = "Amount locked in the device's currently ACTIVE offline certificate, in whole Rupiah. 0 if no active certificate exists.", example = "50000")
        long pouchCommitted
) {}
