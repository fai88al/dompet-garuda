package com.dompetgaruda.api.transaction;

import com.dompetgaruda.api.common.entity.Device;
import com.dompetgaruda.api.common.repository.DeviceRepository;
import com.dompetgaruda.api.transaction.dto.TransactionHistoryPageDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * FR — Phase 3 Feature B: {@code GET /device/transactions} (CLAUDE.md §18).
 *
 * <p>Identifies the caller via the {@code Device-Id} header, same pattern as every other
 * device endpoint (CLAUDE.md §1b). Pure read over {@code ledger_transactions}/
 * {@code ledger_entries} plus in-flight offline state — no writes, no new balance source.
 */
@RestController
@RequestMapping("/device/transactions")
@Profile("api")
@Tag(name = "Device", description = "Device-facing endpoints — identify the caller with a Device-Id header.")
public class TransactionHistoryController {

    private static final int MAX_PAGE_SIZE = 100;

    private final DeviceRepository deviceRepository;
    private final TransactionHistoryService historyService;

    public TransactionHistoryController(DeviceRepository deviceRepository, TransactionHistoryService historyService) {
        this.deviceRepository = deviceRepository;
        this.historyService = historyService;
    }

    @GetMapping
    @Operation(
            summary = "Paginated transaction history for the calling device (Phase 3 Feature B)",
            description = "Covers this device's own POUCH activity and its owning user's ONLINE " +
                          "activity. Read-only — makes no ledger writes. Filter by type and/or a " +
                          "createdAt date range.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transaction page returned (empty content if none match)."),
            @ApiResponse(responseCode = "401", description = "Missing Device-Id header, or device not registered/not ACTIVE.")
    })
    public TransactionHistoryPageDto getTransactions(
            @Parameter(description = "Caller's device id (CLAUDE.md §1a, §1b). Required.", required = true)
            @RequestHeader(name = "Device-Id", required = false) String deviceId,
            @Parameter(description = "Filter by ledger transaction type, e.g. ONLINE_TRANSFER.")
            @RequestParam(required = false) String type,
            @Parameter(description = "Only rows created at or after this instant (ISO-8601).")
            @RequestParam(required = false) Instant from,
            @Parameter(description = "Only rows created strictly before this instant (ISO-8601).")
            @RequestParam(required = false) Instant to,
            @Parameter(description = "Zero-based page index.")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (max 100).")
            @RequestParam(defaultValue = "20") int size) {

        Device device = resolveDevice(deviceId);
        int clampedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int clampedPage = Math.max(page, 0);

        return historyService.getHistory(
                device.getUserId(), List.of(device.getDeviceId()), type, from, to, clampedPage, clampedSize);
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
