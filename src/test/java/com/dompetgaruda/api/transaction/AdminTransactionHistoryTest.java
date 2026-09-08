package com.dompetgaruda.api.transaction;

import com.dompetgaruda.api.ApiIntegrationTestBase;
import com.dompetgaruda.api.DeviceIdTestSupport;
import com.dompetgaruda.api.Ed25519TestSupport;
import com.dompetgaruda.api.device.dto.CreateUserRequest;
import com.dompetgaruda.api.device.dto.CreateUserResponse;
import com.dompetgaruda.api.device.dto.RegisterDeviceRequest;
import com.dompetgaruda.api.device.dto.RegisterDeviceResponse;
import com.dompetgaruda.api.transaction.dto.TransactionHistoryPageDto;
import com.dompetgaruda.api.transaction.dto.TransactionStatus;
import com.dompetgaruda.api.wallet.dto.TopUpRequest;
import com.dompetgaruda.api.wallet.dto.TopUpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Phase 3 Feature B — {@code GET /admin/users/{userId}/transactions}
 * (CLAUDE.md §18, PRD v1.1 §3.4).
 *
 * <p>Cases covered:
 * <ol>
 *   <li>Same response shape as the device endpoint, scoped to the requested user.</li>
 *   <li>Audit log ({@code admin_access_log}) written on every call, INCLUDING calls that
 *       return zero transactions.</li>
 *   <li>Unknown userId returns 404 (and does not write an audit-log row).</li>
 *   <li>Missing admin Bearer token returns 401.</li>
 * </ol>
 */
class AdminTransactionHistoryTest extends ApiIntegrationTestBase {

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    @Test
    void adminHistory_afterTopUp_returnsSuccessRow() {
        UUID userId = createUser("+62860000001");
        registerDevice(userId, "pk-admin-hist-001");
        topUp(userId, 75_000L);

        TransactionHistoryPageDto page = getAdminHistory(userId, 0, 20);

        assertThat(page.content()).anySatisfy(item -> {
            assertThat(item.type()).isEqualTo("TOPUP");
            assertThat(item.status()).isEqualTo(TransactionStatus.SUCCESS);
            assertThat(item.amount()).isEqualTo(75_000L);
        });
    }

    // -------------------------------------------------------------------------
    // Audit log — written on every call, including empty results
    // -------------------------------------------------------------------------

    @Test
    void adminHistory_emptyResult_stillWritesAuditLog() {
        UUID userId = createUser("+62860000002");
        registerDevice(userId, "pk-admin-hist-002");
        // No top-up, no transactions at all for this user.

        long before = countAuditRows(userId);

        TransactionHistoryPageDto page = getAdminHistory(userId, 0, 20);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
        assertThat(countAuditRows(userId))
                .as("admin_access_log must record the access even though zero transactions were returned")
                .isEqualTo(before + 1);
    }

    @Test
    void adminHistory_withResults_writesAuditLogWithAdminIdentityAndParams() {
        UUID userId = createUser("+62860000003");
        registerDevice(userId, "pk-admin-hist-003");
        topUp(userId, 10_000L);

        getAdminHistory(userId, 0, 20);

        var rows = jdbc.queryForList(
                "SELECT admin_user_id, user_id, query_params FROM admin_access_log " +
                "WHERE user_id = ? ORDER BY accessed_at DESC LIMIT 1",
                userId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("admin_user_id")).isEqualTo(TEST_ADMIN_ID);
        assertThat((String) rows.get(0).get("query_params")).contains("page=0", "size=20");
    }

    @Test
    void adminHistory_calledTwice_writesTwoAuditLogRows() {
        UUID userId = createUser("+62860000004");
        registerDevice(userId, "pk-admin-hist-004");

        getAdminHistory(userId, 0, 20);
        getAdminHistory(userId, 0, 20);

        assertThat(countAuditRows(userId)).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // Unknown user
    // -------------------------------------------------------------------------

    @Test
    void adminHistory_unknownUser_returns404AndWritesNoAuditRow() {
        UUID unknownUserId = UUID.randomUUID();
        long before = countAuditRows(unknownUserId);

        ResponseEntity<String> resp = rest.exchange(
                "/admin/users/" + unknownUserId + "/transactions",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(countAuditRows(unknownUserId)).isEqualTo(before);
    }

    // -------------------------------------------------------------------------
    // Auth guard
    // -------------------------------------------------------------------------

    @Test
    void adminHistory_missingBearerToken_returns401() {
        UUID userId = createUser("+62860000005");

        ResponseEntity<String> resp = rest.getForEntity(
                "/admin/users/" + userId + "/transactions", String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -------------------------------------------------------------------------
    // Setup / HTTP helpers
    // -------------------------------------------------------------------------

    private UUID createUser(String phone) {
        return adminPost("/admin/users", new CreateUserRequest("Test User", phone), CreateUserResponse.class).userId();
    }

    private RegisterDeviceResponse registerDevice(UUID userId, String publicKey) {
        return adminPost("/admin/devices",
                new RegisterDeviceRequest(userId, DeviceIdTestSupport.randomDeviceId(),
                        Ed25519TestSupport.derivePublicKeyBase64(publicKey), "Test Device"),
                RegisterDeviceResponse.class);
    }

    private void topUp(UUID userId, long amount) {
        adminPost("/admin/users/" + userId + "/topup", new TopUpRequest(amount, "test-topup"), TopUpResponse.class);
    }

    private TransactionHistoryPageDto getAdminHistory(UUID userId, int page, int size) {
        ResponseEntity<TransactionHistoryPageDto> resp = rest.exchange(
                "/admin/users/" + userId + "/transactions?page=" + page + "&size=" + size,
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                TransactionHistoryPageDto.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private long countAuditRows(UUID userId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM admin_access_log WHERE user_id = ?", Long.class, userId);
        return count == null ? 0L : count;
    }

    private <T> T adminPost(String path, Object body, Class<T> responseType) {
        ResponseEntity<T> resp = rest.postForEntity(path, new HttpEntity<>(body, adminHeaders()), responseType);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("Expected 2xx from %s but got %s: %s", path, resp.getStatusCode(), resp.getBody())
                .isTrue();
        return resp.getBody();
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(testAdminJwt());
        return h;
    }
}
