package com.dompetgaruda.api.admin;

import com.dompetgaruda.api.admin.dto.AnalyticsOverviewDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Phase 3 Feature C — {@code GET /admin/analytics/overview} (CLAUDE.md §19).
 *
 * <p>Standard admin JWT auth, same pattern as every other {@code /admin/**} endpoint
 * (enforced by {@code AdminTokenFilter}). Read-only — never writes to the ledger.
 *
 * <p>{@code @Profile("api")} — {@code ADMIN_JWT_SECRET} is not set in the worker container.
 */
@RestController
@RequestMapping("/admin/analytics")
@Profile("api")
@Tag(name = "Admin", description = "Admin-only endpoints. Require Bearer token in Authorization header.")
public class AdminAnalyticsController {

    private final AdminAnalyticsService service;

    public AdminAnalyticsController(AdminAnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    @Operation(
            summary = "Transaction and device analytics overview (Phase 3 Feature C)",
            description = "dailyVolume/typeDistribution/statusCounts are scoped to [from, to). " +
                          "activeUsers/deviceStatus are current snapshots, independent of the range.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Overview returned."),
            @ApiResponse(responseCode = "400", description = "Missing or unparseable from/to."),
            @ApiResponse(responseCode = "401", description = "Missing or invalid admin Bearer token.")
    })
    public AnalyticsOverviewDto getOverview(
            @Parameter(description = "Range start, inclusive (ISO-8601 instant).") @RequestParam Instant from,
            @Parameter(description = "Range end, exclusive (ISO-8601 instant).") @RequestParam Instant to) {
        return service.getOverview(from, to);
    }
}
