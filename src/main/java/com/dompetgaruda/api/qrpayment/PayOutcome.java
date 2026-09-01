package com.dompetgaruda.api.qrpayment;

import com.dompetgaruda.api.qrpayment.dto.PayPaymentRequestResponse;

/**
 * Result of {@link PaymentRequestService#pay}. Mirrors
 * {@code com.dompetgaruda.api.transfer.TransferOutcome} — see that class for the rationale
 * behind {@code receiverDeviceId} (plain string, not a UUID — CLAUDE.md §1a) and
 * {@code newlyProcessed}.
 */
public record PayOutcome(int status, PayPaymentRequestResponse body, String receiverDeviceId, boolean newlyProcessed) {}
