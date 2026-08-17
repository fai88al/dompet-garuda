package com.dompetgaruda.api.qrpayment;

import com.dompetgaruda.api.qrpayment.dto.PayPaymentRequestResponse;

import java.util.UUID;

/**
 * Result of {@link PaymentRequestService#pay}. Mirrors
 * {@code com.dompetgaruda.api.transfer.TransferOutcome} — see that class for the rationale
 * behind {@code receiverDeviceId} and {@code newlyProcessed}.
 */
public record PayOutcome(int status, PayPaymentRequestResponse body, UUID receiverDeviceId, boolean newlyProcessed) {}
