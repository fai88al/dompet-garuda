package com.dompetgaruda.api.sync;

import com.dompetgaruda.api.auth.DeviceTokenVerifier;
import com.dompetgaruda.api.common.entity.Device;
import com.dompetgaruda.api.sync.dto.SyncBatchRequest;
import com.dompetgaruda.api.sync.dto.SyncBatchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Device-facing endpoint for FR5 — sync batch ingest (POST /device/sync).
 *
 * <p>Accepts a signed batch of offline transactions from a reconnecting device,
 * stores the raw payload in {@code sync_inbox}, and immediately returns 202.
 *
 * <p>This endpoint does NOT validate signatures, does NOT post to the ledger,
 * and does NOT perform settlement. Those are the worker's responsibility (PR7/PR8).
 * Invariant: CLAUDE.md §7 rule 5 — "API never posts to ledger from sync."
 *
 * <p>{@code @Profile("api")} required — device endpoints are api-only.
 */
@RestController
@RequestMapping("/device/sync")
@Profile("api")
@Tag(name = "Device", description = "Device-facing endpoints — authenticate with a device Bearer token.")
public class SyncIngestController {

    private final DeviceTokenVerifier verifier;
    private final SyncIngestService   syncIngestService;

    public SyncIngestController(DeviceTokenVerifier verifier,
                                SyncIngestService syncIngestService) {
        this.verifier          = verifier;
        this.syncIngestService = syncIngestService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "Upload offline transaction batch (FR5)",
            description = "Accepts a signed batch of offline BLE transfers from a reconnecting device. " +
                          "The raw payload is stored verbatim in sync_inbox with status PENDING. " +
                          "No signature verification or ledger writes occur here — the worker " +
                          "handles settlement after polling sync_inbox. " +
                          "Late syncs (after certificate expiry) are accepted and flagged; " +
                          "the worker decides how to settle them.")
    @ApiResponses({
            @ApiResponse(responseCode = "202",
                    description = "Batch accepted and queued. Worker will process asynchronously."),
            @ApiResponse(responseCode = "400",
                    description = "Malformed JSON body or missing required fields (certificateId, transactions)."),
            @ApiResponse(responseCode = "401",
                    description = "Missing or invalid device Bearer token.")
    })
    public SyncBatchResponse ingest(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @Valid @RequestBody SyncBatchRequest request) {
        Device device = resolveDevice(authHeader);
        return syncIngestService.ingest(device, request);
    }

    private Device resolveDevice(String authHeader) {
        String rawToken = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            rawToken = authHeader.substring(7).strip();
        }
        return verifier.verify(rawToken)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Invalid device token"));
    }
}
