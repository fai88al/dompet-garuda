package com.dompetgaruda.api.wallet;

import com.dompetgaruda.api.common.entity.Device;
import com.dompetgaruda.api.common.repository.DeviceRepository;
import com.dompetgaruda.api.wallet.dto.BalanceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Device-facing read endpoint for FR14 — Cek Saldo (check balance).
 *
 * <p>Identifies the caller via a {@code Device-Id} header, looked up directly against
 * {@code devices} (CLAUDE.md §1b, PRD R20) — no Bearer token, no signature check.
 * Returns the online balance and pouch-committed figure. Makes no ledger writes.
 */
@RestController
@RequestMapping("/device")
@Tag(name = "Device", description = "Device-facing endpoints — identify the caller with a Device-Id header.")
public class DeviceBalanceController {

    private final DeviceRepository deviceRepository;
    private final BalanceService balanceService;

    public DeviceBalanceController(DeviceRepository deviceRepository, BalanceService balanceService) {
        this.deviceRepository = deviceRepository;
        this.balanceService   = balanceService;
    }

    @GetMapping("/balance")
    @Operation(
            summary = "Check balance (FR14 — Cek Saldo)",
            description = "Returns the user's authoritative ONLINE ledger balance and the amount locked " +
                          "in the device's active offline certificate. This is a read-only endpoint — " +
                          "it makes no ledger writes.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Balance figures returned."),
            @ApiResponse(responseCode = "401", description = "Missing Device-Id header, or device not registered/not ACTIVE.")
    })
    public BalanceResponse getBalance(
            @Parameter(description = "Caller's device id (CLAUDE.md §1a). Required — identifies the device and its owning user.", required = true)
            @RequestHeader(name = "Device-Id", required = false) String deviceId) {
        Device device = resolveDevice(deviceId);
        return balanceService.getBalance(device);
    }

    private Device resolveDevice(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing Device-Id header");
        }
        return deviceRepository.findById(deviceId)
                .filter(d -> "ACTIVE".equals(d.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Invalid Device-Id"));
    }
}
