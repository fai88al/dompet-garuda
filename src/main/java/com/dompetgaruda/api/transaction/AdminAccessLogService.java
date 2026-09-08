package com.dompetgaruda.api.transaction;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Writes {@code admin_access_log} rows (CLAUDE.md §18, PRD v1.1 §3.4).
 *
 * <p>Written on every call to {@code GET /admin/users/{userId}/transactions}, including
 * calls that return zero transactions — the access itself is what's audited, not the data
 * returned.
 */
@Service
public class AdminAccessLogService {

    private final JdbcTemplate jdbc;

    public AdminAccessLogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(UUID adminUserId, UUID userId, String queryParams) {
        jdbc.update(
                "INSERT INTO admin_access_log (admin_user_id, user_id, query_params) VALUES (?, ?, ?)",
                adminUserId, userId, queryParams);
    }
}
