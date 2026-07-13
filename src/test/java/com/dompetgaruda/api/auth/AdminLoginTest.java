package com.dompetgaruda.api.auth;

import com.dompetgaruda.api.ApiIntegrationTestBase;
import com.dompetgaruda.api.auth.dto.LoginRequest;
import com.dompetgaruda.api.auth.dto.LoginResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for FR15 — POST /admin/auth/login.
 *
 * <p>Cases covered:
 * <ol>
 *   <li>Correct password returns 200 with the ADMIN_API_TOKEN as bearer token.</li>
 *   <li>Wrong password returns 401 with an error message — token never disclosed.</li>
 *   <li>After 5 failed attempts, the 6th returns 429 regardless of password.</li>
 * </ol>
 *
 * <p>{@link LoginAttemptTracker#clearAll()} is called in {@code @BeforeEach} so brute-force
 * state from a previous test in the same Spring context does not bleed into others.
 */
class AdminLoginTest extends ApiIntegrationTestBase {

    private static final String ADMIN_TOKEN = "test-admin-login-token-fr15";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("admin.api-token", () -> ADMIN_TOKEN);
    }

    @Autowired TestRestTemplate rest;
    @Autowired LoginAttemptTracker attemptTracker;

    @BeforeEach
    void resetAttemptTracker() {
        attemptTracker.clearAll();
    }

    @Test
    void login_correctPassword_returns200WithToken() {
        ResponseEntity<LoginResponse> resp = post(ADMIN_TOKEN);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().token()).isEqualTo(ADMIN_TOKEN);
        assertThat(resp.getBody().type()).isEqualTo("Bearer");
    }

    @Test
    void login_wrongPassword_returns401() {
        ResponseEntity<String> resp = rest.postForEntity(
                "/admin/auth/login",
                new HttpEntity<>(new LoginRequest("wrong-password"), jsonHeaders()),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("Invalid password");
        // Ensure the real token is not leaked in the error response
        assertThat(resp.getBody()).doesNotContain(ADMIN_TOKEN);
    }

    @Test
    void login_sixthAttemptAfterFiveFailures_returns429() {
        // Exhaust the 5 allowed attempts
        for (int i = 0; i < LoginAttemptTracker.MAX_ATTEMPTS; i++) {
            ResponseEntity<String> resp = rest.postForEntity(
                    "/admin/auth/login",
                    new HttpEntity<>(new LoginRequest("wrong-" + i), jsonHeaders()),
                    String.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        // 6th attempt — even with correct password — must be rate-limited
        ResponseEntity<String> resp = rest.postForEntity(
                "/admin/auth/login",
                new HttpEntity<>(new LoginRequest(ADMIN_TOKEN), jsonHeaders()),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    private ResponseEntity<LoginResponse> post(String password) {
        return rest.postForEntity(
                "/admin/auth/login",
                new HttpEntity<>(new LoginRequest(password), jsonHeaders()),
                LoginResponse.class);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
