package com.dompetgaruda.api.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "Phase 3 Feature C — GET /admin/analytics/overview response (CLAUDE.md §19).")
public record AnalyticsOverviewDto(
        @Schema(description = "Settled transaction count/amount grouped by date and type, within [from, to).")
        List<DailyVolumeDto> dailyVolume,

        @Schema(description = "Settled transaction count grouped by type, within [from, to).")
        List<TypeDistributionDto> typeDistribution,

        @Schema(description = "SUCCESS/PENDING/FAILED/REVERSED counts within [from, to), same derivation as " +
                               "Feature B (CLAUDE.md §18). REVERSED is always 0 — no reversal mechanism exists yet.")
        Map<String, Long> statusCounts,

        @Schema(description = "Distinct-user counts over rolling windows anchored to now — a current snapshot, " +
                               "independent of [from, to).")
        ActiveUsersDto activeUsers,

        @Schema(description = "Device count grouped by status (ACTIVE/SUSPENDED/LOCKED) — current snapshot, no date filter.")
        Map<String, Long> deviceStatus
) {}
