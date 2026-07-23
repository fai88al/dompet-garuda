package com.dompetgaruda.api.wallet;

import com.dompetgaruda.api.ApiIntegrationTestBase;
import com.dompetgaruda.api.device.dto.CreateUserRequest;
import com.dompetgaruda.api.device.dto.CreateUserResponse;
import com.dompetgaruda.api.wallet.dto.TopUpRequest;
import com.dompetgaruda.api.wallet.dto.TopUpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for FR2 — top-up (POST /admin/users/{userId}/topup).
 * All tests run against a real Postgres container (CLAUDE.md §10 — no DB mocking for money logic).
 *
 * <p>Cases covered:
 * <ol>
 *   <li>Happy path: 201 returned, balance equals top-up amount.</li>
 *   <li>Multiple top-ups: balance accumulates correctly across postings.</li>
 *   <li>Unknown user returns 404.</li>
 *   <li>Zero amount returns 400 (validation).</li>
 *   <li>Negative amount returns 400 (validation).</li>
 *   <li>Missing token returns 401.</li>
 *   <li>Wrong token returns 401.</li>
 * </ol>
 */
class TopUpTest extends ApiIntegrationTestBase {

    @Autowired
    TestRestTemplate rest;

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void topUp_happyPath_returns201AndCreditsBalance() {
        UUID userId = createUser("+62813000001");

        TopUpResponse resp = adminPost(
                "/admin/users/" + userId + "/topup",
                new TopUpRequest(50_000L, "manual-topup-001"),
                TopUpResponse.class);

        assertThat(resp.userId()).isEqualTo(userId);
        assertThat(resp.onlineBalance()).isEqualTo(50_000L);
        assertThat(resp.transactionId()).isPositive();
        assertThat(resp.reference()).isEqualTo("manual-topup-001");
    }

    @Test
    void topUp_multipleTopUps_balanceAccumulates() {
        UUID userId = createUser("+62813000002");

        adminPost("/admin/users/" + userId + "/topup",
                new TopUpRequest(30_000L, "first"), TopUpResponse.class);

        TopUpResponse second = adminPost(
                "/admin/users/" + userId + "/topup",
                new TopUpRequest(20_000L, "second"), TopUpResponse.class);

        // Balance is ledger-derived: 30000 + 20000 = 50000 (CLAUDE.md §7.1)
        assertThat(second.onlineBalance()).isEqualTo(50_000L);
    }

    // -------------------------------------------------------------------------
    // User not found
    // -------------------------------------------------------------------------

    @Test
    void topUp_unknownUser_returns404() {
        ResponseEntity<String> resp = rest.postForEntity(
                "/admin/users/" + UUID.randomUUID() + "/topup",
                new HttpEntity<>(new TopUpRequest(10_000L, "ref"), adminHeaders(testAdminJwt())),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    @Test
    void topUp_zeroAmount_returns400() {
        UUID userId = createUser("+62813000003");

        ResponseEntity<String> resp = rest.postForEntity(
                "/admin/users/" + userId + "/topup",
                new HttpEntity<>(new TopUpRequest(0L, "zero"), adminHeaders(testAdminJwt())),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void topUp_negativeAmount_returns400() {
        UUID userId = createUser("+62813000004");

        ResponseEntity<String> resp = rest.postForEntity(
                "/admin/users/" + userId + "/topup",
                new HttpEntity<>(new TopUpRequest(-1_000L, "neg"), adminHeaders(testAdminJwt())),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // -------------------------------------------------------------------------
    // Admin auth guard
    // -------------------------------------------------------------------------

    @Test
    void topUp_noToken_returns401() {
        UUID userId = createUser("+62813000005");

        ResponseEntity<String> resp = rest.postForEntity(
                "/admin/users/" + userId + "/topup",
                new HttpEntity<>(new TopUpRequest(1_000L, "ref")),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void topUp_wrongToken_returns401() {
        UUID userId = createUser("+62813000006");

        ResponseEntity<String> resp = rest.postForEntity(
                "/admin/users/" + userId + "/topup",
                new HttpEntity<>(new TopUpRequest(1_000L, "ref"), adminHeaders("wrong-token")),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UUID createUser(String phone) {
        return adminPost("/admin/users",
                new CreateUserRequest("Test User", phone),
                CreateUserResponse.class).userId();
    }

    private <T> T adminPost(String path, Object body, Class<T> responseType) {
        ResponseEntity<T> resp = rest.postForEntity(
                path,
                new HttpEntity<>(body, adminHeaders(testAdminJwt())),
                responseType);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("Expected 2xx from %s but got %s: %s", path, resp.getStatusCode(), resp.getBody())
                .isTrue();
        return resp.getBody();
    }

    private HttpHeaders adminHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }
}
