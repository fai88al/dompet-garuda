package com.dompetgaruda.api.auth;

import com.dompetgaruda.api.ApiIntegrationTestBase;
import com.dompetgaruda.api.auth.dto.LoginRequest;
import com.dompetgaruda.api.auth.dto.LoginResponse;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for FR15 — POST /admin/auth/login.
 *
 * <p>A test-specific admin user (distinct from the seeded production accounts) is inserted
 * in {@code @BeforeEach} with a known password and deleted in cleanup. Production account
 * passwords are never referenced in test code (CLAUDE.md §7.9).
 *
 * <p>{@link LoginAttemptTracker#clearAll()} is called in {@code @BeforeEach} so brute-force
 * state from a previous test does not bleed into the next one.
 */
class AdminLoginTest extends ApiIntegrationTestBase {

    private static final String TEST_USERNAME = "test-login@dompetgaruda.com";
    private static final String TEST_PASSWORD = "Test$Password1";
    private static final String TEST_PASSWORD_HASH =
            new BCryptPasswordEncoder(10).encode(TEST_PASSWORD);

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired LoginAttemptTracker attemptTracker;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM admin_users WHERE username = ?", TEST_USERNAME);
        jdbc.update(
                "INSERT INTO admin_users (id, username, password_hash, role) " +
                "VALUES (gen_random_uuid(), ?, ?, 'ADMIN')",
                TEST_USERNAME, TEST_PASSWORD_HASH);
        attemptTracker.clearAll();
    }

    @Test
    void login_correctCredentials_returns200WithJwt() {
        ResponseEntity<LoginResponse> resp = post(TEST_USERNAME, TEST_PASSWORD);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().token()).isNotBlank();
        assertThat(resp.getBody().type()).isEqualTo("Bearer");
        assertThat(resp.getBody().username()).isEqualTo(TEST_USERNAME);
        assertThat(resp.getBody().role()).isEqualTo("ADMIN");

        // Verify the returned token is a valid JWT signed with the test secret
        SecretKey key = Keys.hmacShaKeyFor(HexFormat.of().parseHex(TEST_JWT_SECRET));
        var claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(resp.getBody().token()).getPayload();
        assertThat(claims.get("username", String.class)).isEqualTo(TEST_USERNAME);
        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
    }

    @Test
    void login_wrongPassword_returns401() {
        ResponseEntity<String> resp = postRaw(TEST_USERNAME, "wrong-password");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("Invalid username or password");
        assertThat(resp.getBody()).doesNotContain(TEST_PASSWORD);
    }

    @Test
    void login_unknownUsername_returns401WithSameMessage() {
        ResponseEntity<String> resp = postRaw("nobody@dompetgaruda.com", TEST_PASSWORD);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // Same message as wrong-password to prevent username enumeration (CLAUDE.md §4)
        assertThat(resp.getBody()).contains("Invalid username or password");
    }

    @Test
    void login_sixthAttemptAfterFiveFailures_returns429() {
        for (int i = 0; i < LoginAttemptTracker.MAX_ATTEMPTS; i++) {
            ResponseEntity<String> resp = postRaw(TEST_USERNAME, "wrong-" + i);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        // 6th attempt — even with correct password — must be rate-limited
        ResponseEntity<String> resp = postRaw(TEST_USERNAME, TEST_PASSWORD);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void adminEndpoint_noToken_returns401() {
        ResponseEntity<String> resp = rest.getForEntity("/admin/dashboard", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void adminEndpoint_malformedToken_returns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("this.is.not.a.jwt");
        ResponseEntity<String> resp = rest.exchange(
                "/admin/dashboard", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void adminEndpoint_expiredToken_returns401() {
        SecretKey key = Keys.hmacShaKeyFor(HexFormat.of().parseHex(TEST_JWT_SECRET));
        String expired = Jwts.builder()
                .subject("00000000-0000-0000-0000-000000000001")
                .claim("username", TEST_USERNAME)
                .claim("role", "ADMIN")
                .issuedAt(new Date(System.currentTimeMillis() - 48 * 3600_000L))
                .expiration(new Date(System.currentTimeMillis() - 1_000L))
                .signWith(key)
                .compact();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(expired);
        ResponseEntity<String> resp = rest.exchange(
                "/admin/dashboard", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void adminEndpoint_validToken_passes() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(testAdminJwt());
        ResponseEntity<String> resp = rest.exchange(
                "/admin/dashboard", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        // 200 or 404 are both fine here — we only care that 401 is NOT returned
        assertThat(resp.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- helpers ---

    private ResponseEntity<LoginResponse> post(String username, String password) {
        return rest.postForEntity(
                "/admin/auth/login",
                new HttpEntity<>(new LoginRequest(username, password), jsonHeaders()),
                LoginResponse.class);
    }

    private ResponseEntity<String> postRaw(String username, String password) {
        return rest.postForEntity(
                "/admin/auth/login",
                new HttpEntity<>(new LoginRequest(username, password), jsonHeaders()),
                String.class);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
