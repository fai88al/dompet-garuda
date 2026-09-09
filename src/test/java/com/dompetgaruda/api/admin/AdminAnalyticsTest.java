package com.dompetgaruda.api.admin;

import com.dompetgaruda.api.ApiIntegrationTestBase;
import com.dompetgaruda.api.DeviceIdTestSupport;
import com.dompetgaruda.api.Ed25519TestSupport;
import com.dompetgaruda.api.admin.dto.AnalyticsOverviewDto;
import com.dompetgaruda.api.device.dto.CreateUserRequest;
import com.dompetgaruda.api.device.dto.CreateUserResponse;
import com.dompetgaruda.api.device.dto.RegisterDeviceRequest;
import com.dompetgaruda.api.device.dto.RegisterDeviceResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Phase 3 Feature C — {@code GET /admin/analytics/overview} (CLAUDE.md §19).
 * Runs against a real Postgres container (CLAUDE.md §10).
 *
 * <p>Each test seeds known rows directly via JDBC (same approach as {@link AdminDashboardTest})
 * so the aggregation counts/sums can be asserted exactly, independent of other tests' data —
 * every query is scoped to a {@code [from, to)} window unique to that test, or filtered by a
 * marker only that test inserted.
 */
class AdminAnalyticsTest extends ApiIntegrationTestBase {

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate     jdbc;

    @Test
    void withoutToken_returns401() {
        ResponseEntity<String> resp = rest.getForEntity(
                "/admin/analytics/overview?from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void dailyVolumeAndTypeDistribution_matchSeededTransactions() {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-02T00:00:00Z");
        Instant inRange = from.plus(2, ChronoUnit.HOURS);

        UUID userId = createUser("+62851000001");
        insertTopupAt(userId, 100_000L, inRange);
        insertTopupAt(userId, 50_000L, inRange.plus(1, ChronoUnit.HOURS));
        // outside the window — must not be counted
        insertTopupAt(userId, 999_999L, from.minus(1, ChronoUnit.HOURS));

        AnalyticsOverviewDto overview = getOverview(from, to);

        assertThat(overview.dailyVolume())
                .filteredOn(d -> "TOPUP".equals(d.type()))
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.date()).isEqualTo("2026-05-01");
                    assertThat(d.count()).isEqualTo(2L);
                    assertThat(d.totalAmount()).isEqualTo(150_000L);
                });

        assertThat(overview.typeDistribution())
                .filteredOn(t -> "TOPUP".equals(t.type()))
                .singleElement()
                .satisfies(t -> assertThat(t.count()).isEqualTo(2L));
    }

    @Test
    void statusCounts_successPendingFailedReversed() {
        Instant from = Instant.parse("2026-06-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-02T00:00:00Z");
        Instant inRange = from.plus(1, ChronoUnit.HOURS);

        UUID userId = createUser("+62851000002");
        insertTopupAt(userId, 10_000L, inRange); // 1 SUCCESS
        RegisterDeviceResponse reg = registerDevice(userId, "pk-analytics-status-001");

        UUID batchId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO sync_inbox (batch_id, device_id, raw_payload, status, received_at) " +
                "VALUES (?, ?, CAST(? AS jsonb), 'PENDING', ?)",
                batchId, reg.deviceId(),
                "{\"transactions\":[{\"a\":1},{\"a\":2}]}", java.sql.Timestamp.from(inRange)); // 2 PENDING

        jdbc.update(
                "INSERT INTO flagged_transactions (reason, detail, resolved, offline_txn_id, created_at) " +
                "VALUES ('MALFORMED', 'analytics test flag', false, NULL, ?)",
                java.sql.Timestamp.from(inRange)); // 1 FAILED

        AnalyticsOverviewDto overview = getOverview(from, to);

        assertThat(overview.statusCounts().get("SUCCESS")).isEqualTo(1L);
        assertThat(overview.statusCounts().get("PENDING")).isEqualTo(2L);
        assertThat(overview.statusCounts().get("FAILED")).isEqualTo(1L);
        assertThat(overview.statusCounts().get("REVERSED"))
                .as("no reversal mechanism exists yet, CLAUDE.md §18/§19")
                .isEqualTo(0L);
    }

    @Test
    void activeUsers_countsDistinctUserWithTransactionInWindow() {
        UUID activeUser = createUser("+62851000003");
        UUID idleUser = createUser("+62851000004");
        insertTopupAt(activeUser, 20_000L, Instant.now().minus(2, ChronoUnit.HOURS));
        insertTopupAt(idleUser, 20_000L, Instant.now().minus(40, ChronoUnit.DAYS));

        AnalyticsOverviewDto overview = getOverview(
                Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2030-01-01T00:00:00Z"));

        assertThat(overview.activeUsers().daily()).isGreaterThanOrEqualTo(1L);
        assertThat(overview.activeUsers().sevenDay()).isGreaterThanOrEqualTo(overview.activeUsers().daily());
        assertThat(overview.activeUsers().thirtyDay()).isGreaterThanOrEqualTo(overview.activeUsers().sevenDay());
    }

    @Test
    void deviceStatus_groupsByStatus() {
        UUID userId = createUser("+62851000005");
        RegisterDeviceResponse reg = registerDevice(userId, "pk-analytics-001");
        jdbc.update("UPDATE devices SET status = 'SUSPENDED' WHERE device_id = ?", reg.deviceId());

        AnalyticsOverviewDto overview = getOverview(
                Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2030-01-01T00:00:00Z"));

        assertThat(overview.deviceStatus()).containsKeys("ACTIVE", "SUSPENDED", "LOCKED");
        assertThat(overview.deviceStatus().get("SUSPENDED")).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void overview_neverWritesToLedgerTables() {
        long txnBefore = countRows("ledger_transactions");
        long entryBefore = countRows("ledger_entries");

        getOverview(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"));

        assertThat(countRows("ledger_transactions")).isEqualTo(txnBefore);
        assertThat(countRows("ledger_entries")).isEqualTo(entryBefore);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private long countRows(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private AnalyticsOverviewDto getOverview(Instant from, Instant to) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(testAdminJwt());
        ResponseEntity<AnalyticsOverviewDto> resp = rest.exchange(
                "/admin/analytics/overview?from=" + from + "&to=" + to,
                HttpMethod.GET, new HttpEntity<>(headers), AnalyticsOverviewDto.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private UUID createUser(String phone) {
        return adminPost("/admin/users", new CreateUserRequest("Test User", phone), CreateUserResponse.class).userId();
    }

    private RegisterDeviceResponse registerDevice(UUID userId, String publicKey) {
        return adminPost("/admin/devices",
                new RegisterDeviceRequest(userId, DeviceIdTestSupport.randomDeviceId(), Ed25519TestSupport.derivePublicKeyBase64(publicKey), "Test Device"),
                RegisterDeviceResponse.class);
    }

    private <T> T adminPost(String path, Object body, Class<T> responseType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(testAdminJwt());
        ResponseEntity<T> resp = rest.postForEntity(path, new HttpEntity<>(body, headers), responseType);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("Expected 2xx from %s but got %s: %s", path, resp.getStatusCode(), resp.getBody())
                .isTrue();
        return resp.getBody();
    }

    /** Inserts a settled TOPUP transaction (SYSTEM debit / user ONLINE credit) at a fixed instant. */
    private void insertTopupAt(UUID userId, long amount, Instant createdAt) {
        UUID onlineAccountId = jdbc.queryForObject(
                "SELECT account_id FROM accounts WHERE user_id = ? AND type = 'ONLINE'", UUID.class, userId);
        java.sql.Timestamp ts = java.sql.Timestamp.from(createdAt);
        Long txnId = jdbc.queryForObject(
                "INSERT INTO ledger_transactions (type, reference_type, reference_id, description, created_at) " +
                "VALUES ('TOPUP', 'TEST', 'analytics-test', 'Test topup', ?) RETURNING transaction_id",
                Long.class, ts);
        jdbc.update(
                "INSERT INTO ledger_entries (transaction_id, account_id, direction, amount) " +
                "VALUES (?, '00000000-0000-0000-0000-000000000001', 'DEBIT', ?)",
                txnId, amount);
        jdbc.update(
                "INSERT INTO ledger_entries (transaction_id, account_id, direction, amount) " +
                "VALUES (?, ?, 'CREDIT', ?)",
                txnId, onlineAccountId, amount);
    }
}
