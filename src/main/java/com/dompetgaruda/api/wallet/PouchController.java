package com.dompetgaruda.api.wallet;

import com.dompetgaruda.api.auth.DeviceTokenVerifier;
import com.dompetgaruda.api.common.entity.Device;
import com.dompetgaruda.api.wallet.dto.PouchLoadRequest;
import com.dompetgaruda.api.wallet.dto.PouchLoadResponse;
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
 * Device-facing endpoint for FR3/FR13 — pouch provisioning (POST /device/pouch/load).
 *
 * <p>Requires a device Bearer token. Loads funds from the user's ONLINE account
 * into the device's POUCH and issues a server-signed offline certificate.
 *
 * <p>{@code @Profile("api")} required: delegates to {@link PouchService} which is
 * also api-only (injects server.signing-key).
 */
@RestController
@RequestMapping("/device/pouch")
@Profile("api")
@Tag(name = "Device", description = "Device-facing endpoints — authenticate with a device Bearer token.")
public class PouchController {

    private final DeviceTokenVerifier verifier;
    private final PouchService        pouchService;

    public PouchController(DeviceTokenVerifier verifier, PouchService pouchService) {
        this.verifier     = verifier;
        this.pouchService = pouchService;
    }

    @PostMapping("/load")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Load funds into offline pouch (FR3/FR13)",
            description = "Debits the user's ONLINE ledger account and credits the device's POUCH account. " +
                          "Issues a server-signed offline certificate the device uses to prove authorised spend. " +
                          "Fails with 409 if an ACTIVE certificate already exists for this device.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Pouch loaded; offline certificate issued."),
            @ApiResponse(responseCode = "400", description = "Validation failed — amount ≤ 0 or exceeds pouch max."),
            @ApiResponse(responseCode = "401", description = "Missing or invalid device Bearer token."),
            @ApiResponse(responseCode = "409", description = "Device already has an active offline certificate."),
            @ApiResponse(responseCode = "422", description = "Insufficient online balance.")
    })
    public PouchLoadResponse load(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @Valid @RequestBody PouchLoadRequest request) {
        Device device = resolveDevice(authHeader);
        return pouchService.load(device, request);
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
