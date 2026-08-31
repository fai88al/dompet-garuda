package com.dompetgaruda.api.transfer;

import com.dompetgaruda.api.transfer.dto.TransferResponse;

/**
 * Result of {@link TransferService#transfer}.
 *
 * @param status           HTTP status to return (200 for both a fresh transfer and a replay)
 * @param body             the response body (identical on replay — CLAUDE.md §14.4)
 * @param receiverDeviceId the receiver's registered device, if any (plain string, not a UUID
 *                         — CLAUDE.md §1a) — used for the best-effort {@code payment-received}
 *                         MQTT hint; {@code null} if none registered
 * @param newlyProcessed   {@code false} when this outcome came from an idempotency replay —
 *                         the controller uses this to skip re-publishing the MQTT hint
 */
public record TransferOutcome(int status, TransferResponse body, String receiverDeviceId, boolean newlyProcessed) {}
