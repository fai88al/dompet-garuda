package com.dompetgaruda.api.transaction;

import com.dompetgaruda.api.auth.RoleGuard;
import com.dompetgaruda.api.common.entity.Device;
import com.dompetgaruda.api.common.repository.DeviceRepository;
import com.dompetgaruda.api.common.repository.UserRepository;
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
import java.util.UUID;

/**
 * FR — Phase 3 Feature B: {@code GET /admin/users/{userId}/transactions} (CLAUDE.md §18).
 *
 * <p>Standard admin JWT auth (enforced by {@code AdminTokenFilter} on every {@code /admin/**}
 * path). Every call — including ones that return zero transactions — is recorded to
 * {@code admin_access_log} (PRD v1.1 §3.4): the access itself is what's audited.
 *
 * <p>{@code @Profile("api")} — {@code ADMIN_JWT_SECRET} is not set in the worker container.
 */
@RestController
@RequestMapping("/admin/users/{userId}/transactions")
@Profile("api")
@Tag(name = "Admin", description = "Admin-only endpoints. Require Bearer token in Authorization header.")
public class AdminTransactionHistoryController {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final DeviceRepository deviceRepository;
    private final TransactionHistoryService historyService;
    private final AdminAccessLogService accessLog;

    public AdminTransactionHistoryController(
            UserRepository userRepository, DeviceRepository deviceRepository,
            TransactionHistoryService historyService, AdminAccessLogService accessLog) {
        this.userRepository = userRepository;
        this.deviceRepository = deviceRepository;
        this.historyService = historyService;
        this.accessLog = accessLog;
    }

    @GetMapping
    @Operation(
            summary = "Paginated transaction history for a user, across all their devices (Phase 3 Feature B)",
            description = "Same response shape as GET /device/transactions. Every call is written to " +
                          "admin_access_log, including calls that return zero transactions (PRD v1.1 §3.4).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transaction page returned (empty content if none match)."),
            @ApiResponse(responseCode = "401", description = "Missing or invalid admin Bearer token."),
            @ApiResponse(responseCode = "404", description = "User not found.")
    })
    public TransactionHistoryPageDto getTransactions(
            @Parameter(description = "User UUID.") @PathVariable UUID userId,
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

        if (!userRepository.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + userId);
        }

        List<String> deviceIds = deviceRepository.findAllByUserId(userId).stream()
                .map(Device::getDeviceId)
                .toList();

        int clampedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int clampedPage = Math.max(page, 0);

        TransactionHistoryPageDto result =
                historyService.getHistory(userId, deviceIds, type, from, to, clampedPage, clampedSize);

        String queryParams = "type=" + type + ", from=" + from + ", to=" + to +
                              ", page=" + clampedPage + ", size=" + clampedSize;
        accessLog.record(RoleGuard.currentUserId(), userId, queryParams);

        return result;
    }
}
