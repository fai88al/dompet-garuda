package com.dompetgaruda.api.wallet;

import com.dompetgaruda.api.auth.DeviceTokenVerifier;
import com.dompetgaruda.api.common.entity.Device;
import com.dompetgaruda.api.wallet.dto.BalanceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Device-facing read endpoint for FR14 — Cek Saldo (check balance).
 *
 * <p>Requires a device Bearer token in the Authorization header.
 * Returns the online balance and pouch-committed figure. Makes no ledger writes.
 */
@RestController
@RequestMapping("/device")
@Tag(name = "Device", description = "Device-facing endpoints — authenticate with a device Bearer token.")
public class DeviceBalanceController {

    private final DeviceTokenVerifier verifier;
    private final BalanceService balanceService;

    public DeviceBalanceController(DeviceTokenVerifier verifier, BalanceService balanceService) {
        this.verifier       = verifier;
        this.balanceService = balanceService;
    }

    @GetMapping("/balance")
    @Operation(
            summary = "Check balance (FR14 — Cek Saldo)",
            description = "Returns the user's authoritative ONLINE ledger balance and the amount locked " +
                          "in the device's active offline certificate. This is a read-only endpoint — " +
                          "it makes no ledger writes.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Balance figures returned."),
            @ApiResponse(responseCode = "401", description = "Missing or invalid device Bearer token.")
    })
    public BalanceResponse getBalance(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
        Device device = resolveDevice(authHeader);
        return balanceService.getBalance(device);
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
