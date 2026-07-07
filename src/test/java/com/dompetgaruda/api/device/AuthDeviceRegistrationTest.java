package com.dompetgaruda.api.device;

import com.dompetgaruda.api.ApiIntegrationTestBase;
import com.dompetgaruda.api.common.repository.DeviceRepository;
import com.dompetgaruda.api.device.dto.CreateUserRequest;
import com.dompetgaruda.api.device.dto.CreateUserResponse;
import com.dompetgaruda.api.device.dto.RegisterDeviceRequest;
import com.dompetgaruda.api.device.dto.RegisterDeviceResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for admin auth and device registration (FR1).
 * All tests run against a real Postgres container — no mocking of the database.
 * See CLAUDE.md §10.
 */
class AuthDeviceRegistrationTest extends ApiIntegrationTestBase {

    private static final String ADMIN_TOKEN = "test-admin-secret-for-registration";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("admin.api-token", () -> ADMIN_TOKEN);
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    DeviceRepository deviceRepository;

    // -------------------------------------------------------------------------
    // Admin auth guard
    // -------------------------------------------------------------------------

    @Test
    void adminEndpoint_returns401_withoutToken() {
        ResponseEntity<String> resp = rest.postForEntity(
                "/admin/users",
                new HttpEntity<>(new CreateUserRequest("Alice", "+62811000001")),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void adminEndpoint_returns401_withWrongToken() {
        ResponseEntity<String> resp = rest.postForEntity(
                "/admin/users",
                new HttpEntity<>(new CreateUserRequest("Bob", "+62811000002"), adminHeaders("wrong-token")),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -------------------------------------------------------------------------
    // User creation
    // -------------------------------------------------------------------------

    @Test
    void createUser_happyPath_returns201WithOnlineAccount() {
        CreateUserResponse resp = adminPost(
                "/admin/users",
                new CreateUserRequest("Citra Dewi", "+62811000003"),
                CreateUserResponse.class);

        assertThat(resp.userId()).isNotNull();
        assertThat(resp.fullName()).isEqualTo("Citra Dewi");
        assertThat(resp.status()).isEqualTo("ACTIVE");
        assertThat(resp.onlineAccountId()).isNotNull();
    }

    @Test
    void createUser_duplicatePhone_returns409() {
        adminPost("/admin/users",
                new CreateUserRequest("Dani", "+62811000004"),
                CreateUserResponse.class);

        ResponseEntity<String> resp = rest.postForEntity(
                "/admin/users",
                new HttpEntity<>(new CreateUserRequest("Dani 2", "+62811000004"), adminHeaders(ADMIN_TOKEN)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // -------------------------------------------------------------------------
    // Device registration — happy path
    // -------------------------------------------------------------------------

    @Test
    void registerDevice_happyPath_returnsTokenOnce() {
        UUID userId = createUser("+62812000001");
        String pubKey = "pk_" + UUID.randomUUID();

        RegisterDeviceResponse resp = adminPost(
                "/admin/devices",
                new RegisterDeviceRequest(userId, pubKey, "Device A"),
                RegisterDeviceResponse.class);

        assertThat(resp.deviceId()).isNotNull();
        assertThat(resp.userId()).isEqualTo(userId);
        assertThat(resp.pouchAccountId()).isNotNull();
        assertThat(resp.deviceToken()).isNotBlank();

        // The token is 64 hex chars (32 random bytes hex-encoded).
        assertThat(resp.deviceToken()).hasSize(64).matches("[0-9a-f]+");
    }

    // -------------------------------------------------------------------------
    // Invariant: stored hash ≠ plaintext token (CLAUDE.md §4)
    // -------------------------------------------------------------------------

    @Test
    void registerDevice_storedHashIsNotPlaintext() {
        UUID userId = createUser("+62812000002");
        String pubKey = "pk_" + UUID.randomUUID();

        RegisterDeviceResponse resp = adminPost(
                "/admin/devices",
                new RegisterDeviceRequest(userId, pubKey, "Device B"),
                RegisterDeviceResponse.class);

        String returnedToken = resp.deviceToken();
        String storedHash = deviceRepository.findById(resp.deviceId())
                .orElseThrow()
                .getDeviceTokenHash();

        assertThat(storedHash).isNotEqualTo(returnedToken);
        assertThat(storedHash).hasSize(64); // SHA-256 hex is always 64 chars
    }

    // -------------------------------------------------------------------------
    // Invariant: duplicate public key rejected (FR1)
    // -------------------------------------------------------------------------

    @Test
    void registerDevice_duplicatePublicKey_returns409() {
        UUID userId = createUser("+62812000003");
        String pubKey = "pk_shared_" + UUID.randomUUID();

        adminPost("/admin/devices",
                new RegisterDeviceRequest(userId, pubKey, "Device C1"),
                RegisterDeviceResponse.class);

        ResponseEntity<String> resp = rest.postForEntity(
                "/admin/devices",
                new HttpEntity<>(new RegisterDeviceRequest(userId, pubKey, "Device C2"), adminHeaders(ADMIN_TOKEN)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // -------------------------------------------------------------------------
    // Invariant: max 3 devices per user (FR1 / Decision R3)
    // -------------------------------------------------------------------------

    @Test
    void registerDevice_fourthDevice_returns422() {
        UUID userId = createUser("+62812000004");

        for (int i = 1; i <= 3; i++) {
            adminPost("/admin/devices",
                    new RegisterDeviceRequest(userId, "pk_user4_" + i, "Device " + i),
                    RegisterDeviceResponse.class);
        }

        ResponseEntity<String> resp = rest.postForEntity(
                "/admin/devices",
                new HttpEntity<>(new RegisterDeviceRequest(userId, "pk_user4_4", "Device 4"), adminHeaders(ADMIN_TOKEN)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
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
                new HttpEntity<>(body, adminHeaders(ADMIN_TOKEN)),
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
