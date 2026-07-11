package com.dompetgaruda.api.admin;

import com.dompetgaruda.api.admin.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Read-only admin dashboard endpoints (FR10).
 *
 * All endpoints require Bearer ADMIN_API_TOKEN (enforced by AdminTokenFilter).
 * No money movement and no writes to any table occur here.
 *
 * @Profile("api") — ADMIN_API_TOKEN is not set in the worker container.
 */
@RestController
@RequestMapping("/admin")
@Profile("api")
@Tag(name = "Admin", description = "Admin-only endpoints for user and device provisioning. Require Bearer token in Authorization header.")
public class AdminDashboardController {

    private final AdminDashboardService service;

    public AdminDashboardController(AdminDashboardService service) {
        this.service = service;
    }

    @GetMapping("/users")
    @Operation(summary = "List all users with derived online balances and device counts.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "User list (empty array if none)."),
        @ApiResponse(responseCode = "401", description = "Missing or invalid admin Bearer token.")
    })
    public List<UserSummaryDto> listUsers() {
        return service.listUsers();
    }

    @GetMapping("/users/{userId}")
    @Operation(summary = "Get a single user with their devices and derived online balance.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "User found."),
        @ApiResponse(responseCode = "401", description = "Missing or invalid admin Bearer token."),
        @ApiResponse(responseCode = "404", description = "User not found.")
    })
    public UserDetailDto getUser(
            @Parameter(description = "User UUID.") @PathVariable UUID userId) {
        return service.getUser(userId);
    }

    @GetMapping("/devices")
    @Operation(summary = "List all devices with their active certificates.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Device list (empty array if none)."),
        @ApiResponse(responseCode = "401", description = "Missing or invalid admin Bearer token.")
    })
    public List<DeviceWithCertDto> listDevices() {
        return service.listDevices();
    }

    @GetMapping("/certificates")
    @Operation(summary = "List all offline certificates, newest first. Filter by status with ?status=.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Certificate list (empty array if none)."),
        @ApiResponse(responseCode = "401", description = "Missing or invalid admin Bearer token.")
    })
    public List<CertificateDto> listCertificates(
            @Parameter(description = "Optional status filter: ACTIVE, SETTLED, EXPIRED, or REVOKED.")
            @RequestParam(required = false) String status) {
        return service.listCertificates(status);
    }

    @GetMapping("/sync")
    @Operation(summary = "List recent sync_inbox batches (newest first). raw_payload is excluded intentionally.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Sync batch list (empty array if none)."),
        @ApiResponse(responseCode = "401", description = "Missing or invalid admin Bearer token.")
    })
    public List<SyncBatchDto> listSync(
            @Parameter(description = "Maximum rows to return (default 50, max 200).")
            @RequestParam(defaultValue = "50") int limit) {
        int clampedLimit = Math.min(Math.max(limit, 1), 200);
        return service.listSync(clampedLimit);
    }

    @GetMapping("/flagged")
    @Operation(summary = "List flagged transactions. Returns unresolved rows by default; pass ?resolved=true to include resolved ones.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Flagged transaction list (empty array if none)."),
        @ApiResponse(responseCode = "401", description = "Missing or invalid admin Bearer token.")
    })
    public List<FlaggedTransactionDto> listFlagged(
            @Parameter(description = "Set to true to include resolved flags alongside unresolved ones.")
            @RequestParam(defaultValue = "false") boolean resolved) {
        return service.listFlagged(resolved);
    }
}
