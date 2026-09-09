package com.dompetgaruda.api.admin;

import com.dompetgaruda.api.admin.dto.ActiveUsersDto;
import com.dompetgaruda.api.admin.dto.AnalyticsOverviewDto;
import com.dompetgaruda.api.admin.dto.DailyVolumeDto;
import com.dompetgaruda.api.admin.dto.TypeDistributionDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 3 Feature C — read-only aggregation queries backing {@code GET /admin/analytics/overview}
 * (CLAUDE.md §19). Direct SQL, not ORM-generated, matching this project's money-query philosophy
 * (§2, §7). Never writes to any table.
 *
 * <p>{@code statusCounts} reuses the same SUCCESS/PENDING/FAILED/REVERSED derivation as Feature B
 * (CLAUDE.md §18): SUCCESS from settled {@code ledger_transactions}, PENDING from offline
 * transactions still sitting in {@code sync_inbox}, FAILED from {@code flagged_transactions} rows
 * that never posted, REVERSED always 0 (no reversal mechanism exists yet).
 *
 * <p>{@code activeUsers} — "active" = distinct user with a settled ledger transaction (via their
 * ONLINE account or a POUCH account on one of their devices) inside the window. No other activity
 * signal (e.g. login) exists in the schema. Windows are rolling, anchored to now — independent of
 * the requested {@code [from, to)} range, same as {@code deviceStatus}.
 */
@Service
public class AdminAnalyticsService {

    private final JdbcTemplate jdbc;

    public AdminAnalyticsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AnalyticsOverviewDto getOverview(Instant from, Instant to) {
        Timestamp fromTs = Timestamp.from(from);
        Timestamp toTs = Timestamp.from(to);

        List<DailyVolumeDto> dailyVolume = jdbc.query(
                "SELECT DATE(lt.created_at) AS bucket_date, lt.type AS type, " +
                "       COUNT(*) AS cnt, COALESCE(SUM(le.amount), 0) AS total_amount " +
                "FROM ledger_transactions lt " +
                "JOIN ledger_entries le ON le.transaction_id = lt.transaction_id AND le.direction = 'DEBIT' " +
                "WHERE lt.created_at >= ? AND lt.created_at < ? " +
                "GROUP BY DATE(lt.created_at), lt.type " +
                "ORDER BY bucket_date, type",
                (rs, rowNum) -> new DailyVolumeDto(
                        rs.getDate("bucket_date").toString(),
                        rs.getString("type"),
                        rs.getLong("cnt"),
                        rs.getLong("total_amount")),
                fromTs, toTs);

        List<TypeDistributionDto> typeDistribution = jdbc.query(
                "SELECT type, COUNT(*) AS cnt FROM ledger_transactions " +
                "WHERE created_at >= ? AND created_at < ? " +
                "GROUP BY type ORDER BY type",
                (rs, rowNum) -> new TypeDistributionDto(rs.getString("type"), rs.getLong("cnt")),
                fromTs, toTs);

        Map<String, Long> statusCounts = new LinkedHashMap<>();
        statusCounts.put("SUCCESS", jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions WHERE created_at >= ? AND created_at < ?",
                Long.class, fromTs, toTs));
        statusCounts.put("PENDING", jdbc.queryForObject(
                "SELECT COALESCE(SUM(jsonb_array_length(raw_payload -> 'transactions')), 0) " +
                "FROM sync_inbox WHERE status IN ('PENDING', 'PROCESSING') " +
                "AND received_at >= ? AND received_at < ?",
                Long.class, fromTs, toTs));
        statusCounts.put("FAILED", jdbc.queryForObject(
                "SELECT COUNT(*) FROM flagged_transactions " +
                "WHERE offline_txn_id IS NULL AND created_at >= ? AND created_at < ?",
                Long.class, fromTs, toTs));
        statusCounts.put("REVERSED", 0L);

        Instant now = Instant.now();
        ActiveUsersDto activeUsers = new ActiveUsersDto(
                countActiveUsers(now.minus(1, ChronoUnit.DAYS), now),
                countActiveUsers(now.minus(7, ChronoUnit.DAYS), now),
                countActiveUsers(now.minus(30, ChronoUnit.DAYS), now));

        Map<String, Long> deviceStatus = new LinkedHashMap<>();
        deviceStatus.put("ACTIVE", 0L);
        deviceStatus.put("SUSPENDED", 0L);
        deviceStatus.put("LOCKED", 0L);
        jdbc.query("SELECT status, COUNT(*) AS cnt FROM devices GROUP BY status", (RowCallbackHandler) rs ->
                deviceStatus.put(rs.getString("status"), rs.getLong("cnt")));

        return new AnalyticsOverviewDto(dailyVolume, typeDistribution, statusCounts, activeUsers, deviceStatus);
    }

    private long countActiveUsers(Instant windowStart, Instant windowEnd) {
        return jdbc.queryForObject(
                "SELECT COUNT(DISTINCT COALESCE(a.user_id, d.user_id)) " +
                "FROM ledger_transactions lt " +
                "JOIN ledger_entries le ON le.transaction_id = lt.transaction_id " +
                "JOIN accounts a ON a.account_id = le.account_id " +
                "LEFT JOIN devices d ON d.device_id = a.device_id " +
                "WHERE a.type <> 'SYSTEM' AND lt.created_at >= ? AND lt.created_at < ?",
                Long.class, Timestamp.from(windowStart), Timestamp.from(windowEnd));
    }
}
