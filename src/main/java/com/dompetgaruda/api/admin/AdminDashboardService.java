package com.dompetgaruda.api.admin;

import com.dompetgaruda.api.admin.dto.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only queries for the admin dashboard. No money movement, no writes.
 *
 * Balance is always derived as SUM(CREDIT) - SUM(DEBIT) from ledger_entries (CLAUDE.md §7.1).
 * There is no stored balance column.
 *
 * Timestamp mapping: PostgreSQL JDBC maps TIMESTAMPTZ to java.sql.Timestamp via
 * ResultSet.getTimestamp(). We call .toInstant() on the result. Direct
 * rs.getObject(col, Instant.class) is not supported by the Postgres JDBC driver.
 */
@Service
@Profile("api")
public class AdminDashboardService {

    private final JdbcTemplate jdbc;

    public AdminDashboardService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // -------------------------------------------------------------------------
    // GET /admin/users
    // -------------------------------------------------------------------------

    public List<UserSummaryDto> listUsers() {
        return jdbc.query(
                "SELECT u.user_id, u.full_name, u.phone, u.status, u.created_at, " +
                "  COALESCE(SUM(CASE WHEN le.direction = 'CREDIT' THEN le.amount ELSE -le.amount END), 0) AS online_balance, " +
                "  COUNT(DISTINCT d.device_id) AS device_count " +
                "FROM users u " +
                "LEFT JOIN accounts a ON a.user_id = u.user_id AND a.type = 'ONLINE' " +
                "LEFT JOIN ledger_entries le ON le.account_id = a.account_id " +
                "LEFT JOIN devices d ON d.user_id = u.user_id " +
                "GROUP BY u.user_id, u.full_name, u.phone, u.status, u.created_at " +
                "ORDER BY u.created_at DESC",
                (rs, rowNum) -> new UserSummaryDto(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("phone"),
                        rs.getString("status"),
                        rs.getLong("online_balance"),
                        rs.getLong("device_count"),
                        rsInstant(rs, "created_at")
                ));
    }

    // -------------------------------------------------------------------------
    // GET /admin/users/{userId}
    // -------------------------------------------------------------------------

    public UserDetailDto getUser(UUID userId) {
        List<UserSummaryDto> rows = jdbc.query(
                "SELECT u.user_id, u.full_name, u.phone, u.status, u.created_at, " +
                "  COALESCE(SUM(CASE WHEN le.direction = 'CREDIT' THEN le.amount ELSE -le.amount END), 0) AS online_balance, " +
                "  COUNT(DISTINCT d.device_id) AS device_count " +
                "FROM users u " +
                "LEFT JOIN accounts a ON a.user_id = u.user_id AND a.type = 'ONLINE' " +
                "LEFT JOIN ledger_entries le ON le.account_id = a.account_id " +
                "LEFT JOIN devices d ON d.user_id = u.user_id " +
                "WHERE u.user_id = ? " +
                "GROUP BY u.user_id, u.full_name, u.phone, u.status, u.created_at",
                (rs, rowNum) -> new UserSummaryDto(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("phone"),
                        rs.getString("status"),
                        rs.getLong("online_balance"),
                        rs.getLong("device_count"),
                        rsInstant(rs, "created_at")
                ),
                userId);

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + userId);
        }

        UserSummaryDto u = rows.get(0);

        List<DeviceSummaryDto> devices = jdbc.query(
                "SELECT device_id, status, registered_at FROM devices WHERE user_id = ? ORDER BY registered_at",
                (rs, rowNum) -> new DeviceSummaryDto(
                        rs.getObject("device_id", UUID.class),
                        rs.getString("status"),
                        rsInstant(rs, "registered_at")
                ),
                userId);

        return new UserDetailDto(
                u.userId(), u.fullName(), u.phone(), u.status(),
                u.onlineBalance(), u.deviceCount(), u.createdAt(), devices);
    }

    // -------------------------------------------------------------------------
    // GET /admin/devices
    // -------------------------------------------------------------------------

    public List<DeviceWithCertDto> listDevices() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT d.device_id, d.user_id, u.phone AS user_phone, d.status, d.last_counter, d.registered_at, " +
                "  oc.certificate_id, oc.issued_amount, oc.expires_at, oc.status AS cert_status " +
                "FROM devices d " +
                "JOIN users u ON u.user_id = d.user_id " +
                "LEFT JOIN offline_certificates oc ON oc.device_id = d.device_id AND oc.status = 'ACTIVE' " +
                "ORDER BY d.registered_at DESC");

        return rows.stream().map(row -> {
            ActiveCertDto cert = null;
            if (row.get("certificate_id") != null) {
                cert = new ActiveCertDto(
                        (UUID) row.get("certificate_id"),
                        ((Number) row.get("issued_amount")).longValue(),
                        toInstant(row.get("expires_at")),
                        (String) row.get("cert_status")
                );
            }
            return new DeviceWithCertDto(
                    (UUID) row.get("device_id"),
                    (UUID) row.get("user_id"),
                    (String) row.get("user_phone"),
                    (String) row.get("status"),
                    ((Number) row.get("last_counter")).longValue(),
                    toInstant(row.get("registered_at")),
                    cert
            );
        }).toList();
    }

    // -------------------------------------------------------------------------
    // GET /admin/certificates
    // -------------------------------------------------------------------------

    public List<CertificateDto> listCertificates(String statusFilter) {
        String sql = "SELECT oc.certificate_id, oc.device_id, u.phone AS user_phone, " +
                "  oc.issued_amount, oc.status, oc.issued_at, oc.expires_at, oc.settled_at " +
                "FROM offline_certificates oc " +
                "JOIN devices d ON d.device_id = oc.device_id " +
                "JOIN users u ON u.user_id = d.user_id ";

        if (statusFilter != null && !statusFilter.isBlank()) {
            String filter = statusFilter;
            return jdbc.query(
                    sql + "WHERE oc.status = ? ORDER BY oc.issued_at DESC",
                    (rs, rowNum) -> mapCertificate(rs),
                    filter);
        }

        return jdbc.query(
                sql + "ORDER BY oc.issued_at DESC",
                (rs, rowNum) -> mapCertificate(rs));
    }

    // -------------------------------------------------------------------------
    // GET /admin/sync
    // -------------------------------------------------------------------------

    public List<SyncBatchDto> listSync(int limit) {
        return jdbc.query(
                "SELECT batch_id, device_id, status, synced_after_expiry, received_at, processed_at, error_reason " +
                "FROM sync_inbox " +
                "ORDER BY received_at DESC " +
                "LIMIT ?",
                (rs, rowNum) -> new SyncBatchDto(
                        rs.getObject("batch_id", UUID.class),
                        rs.getObject("device_id", UUID.class),
                        rs.getString("status"),
                        rs.getBoolean("synced_after_expiry"),
                        rsInstant(rs, "received_at"),
                        rsInstant(rs, "processed_at"),
                        rs.getString("error_reason")
                ),
                limit);
    }

    // -------------------------------------------------------------------------
    // GET /admin/flagged
    // -------------------------------------------------------------------------

    public List<FlaggedTransactionDto> listFlagged(boolean includeResolved) {
        String sql = "SELECT flag_id, reason, detail, created_at, offline_txn_id, batch_id, certificate_id " +
                "FROM flagged_transactions ";
        if (!includeResolved) {
            sql += "WHERE resolved = false ";
        }
        sql += "ORDER BY created_at DESC";

        return jdbc.query(sql,
                (rs, rowNum) -> new FlaggedTransactionDto(
                        rs.getLong("flag_id"),
                        rs.getString("reason"),
                        rs.getString("detail"),
                        rsInstant(rs, "created_at"),
                        rs.getObject("offline_txn_id", UUID.class),
                        rs.getObject("batch_id", UUID.class),
                        rs.getObject("certificate_id", UUID.class)
                ));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private CertificateDto mapCertificate(ResultSet rs) throws SQLException {
        return new CertificateDto(
                rs.getObject("certificate_id", UUID.class),
                rs.getObject("device_id", UUID.class),
                rs.getString("user_phone"),
                rs.getLong("issued_amount"),
                rs.getString("status"),
                rsInstant(rs, "issued_at"),
                rsInstant(rs, "expires_at"),
                rsInstant(rs, "settled_at")
        );
    }

    /** Reads a nullable TIMESTAMPTZ column as Instant. */
    private static Instant rsInstant(ResultSet rs, String col) throws SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(col);
        return ts != null ? ts.toInstant() : null;
    }

    /** Converts a raw JDBC map value (Timestamp / OffsetDateTime) to Instant. */
    private static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant i) return i;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        throw new IllegalStateException("Cannot convert to Instant: " + value.getClass());
    }
}
