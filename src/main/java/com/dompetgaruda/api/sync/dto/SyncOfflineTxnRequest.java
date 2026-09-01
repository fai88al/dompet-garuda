package com.dompetgaruda.api.sync.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * A single offline BLE transfer within an uploaded sync batch.
 * All fields are stored as-is; content validation is the worker's responsibility.
 */
@Schema(description = "A single offline BLE transfer within the sync batch.")
public record SyncOfflineTxnRequest(

        @Schema(description = "Device-generated UUID for this offline transaction.",
                example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        UUID offlineTxnId,

        @Schema(description = "Identifier of the receiving device — plain string, not a UUID (CLAUDE.md §1a).",
                example = "B2C3D4E5F6A7")
        String receiverDeviceId,

        @Schema(description = "Transfer amount in whole Rupiah.", example = "50000")
        long amount,

        @Schema(description = "Sender's monotonic counter for replay protection.", example = "1")
        long counter,

        @Schema(description = "Device clock timestamp (untrusted, informational only).",
                example = "2026-07-07T10:00:00Z")
        Instant deviceTimestamp,

        @Schema(description = "Base64-encoded Ed25519 signature by the sender device.",
                example = "base64-encoded-bytes")
        String senderSignature,

        @Schema(description = "Base64-encoded Ed25519 acknowledgement signature by the receiver device.",
                example = "base64-encoded-bytes")
        String receiverSignature,

        @Schema(description = "Optional origin of this transaction: 'BLE' (default) or 'QR' " +
                "(FR23). Informational only — never affects verification, signature, or " +
                "pouch-limit checks. Defaults to 'BLE' if absent.",
                example = "BLE")
        String origin
) {}
