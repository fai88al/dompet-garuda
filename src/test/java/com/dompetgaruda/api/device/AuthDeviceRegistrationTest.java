package com.dompetgaruda.api.device;

import com.dompetgaruda.api.ApiIntegrationTestBase;
import com.dompetgaruda.api.DeviceIdTestSupport;
import com.dompetgaruda.api.common.repository.DeviceRepository;
import com.dompetgaruda.api.device.dto.CreateUserRequest;
import com.dompetgaruda.api.device.dto.CreateUserResponse;
import com.dompetgaruda.api.device.dto.RegisterDeviceRequest;
import com.dompetgaruda.api.device.dto.RegisterDeviceResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for admin auth and device registration (FR1).
 * All tests run against a real Postgres container — no mocking of the database.
 * See CLAUDE.md §10.
 */
class AuthDeviceRegistrationTest extends ApiIntegrationTestBase {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    DeviceRepository deviceRepository;

    @Autowired
    JdbcTemplate jdbc;

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
                new HttpEntity<>(new CreateUserRequest("Dani 2", "+62811000004"), adminHeaders(testAdminJwt())),
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

        String deviceId = DeviceIdTestSupport.randomDeviceId();
        RegisterDeviceResponse resp = adminPost(
                "/admin/devices",
                new RegisterDeviceRequest(userId, deviceId, pubKey, "Device A"),
                RegisterDeviceResponse.class);

        assertThat(resp.deviceId()).isEqualTo(deviceId);
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
                new RegisterDeviceRequest(userId, DeviceIdTestSupport.randomDeviceId(), pubKey, "Device B"),
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
                new RegisterDeviceRequest(userId, DeviceIdTestSupport.randomDeviceId(), pubKey, "Device C1"),
                RegisterDeviceResponse.class);

        ResponseEntity<String> resp = rest.postForEntity(
                "/admin/devices",
                new HttpEntity<>(new RegisterDeviceRequest(userId, DeviceIdTestSupport.randomDeviceId(), pubKey, "Device C2"), adminHeaders(testAdminJwt())),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // -------------------------------------------------------------------------
    // Invariant: duplicate deviceId rejected (FR27 — deviceId is now a client-
    // supplied primary key, so a reused id must be a clean 409, not a raw PK
    // violation surfacing as 500)
    // -------------------------------------------------------------------------

    @Test
    void registerDevice_duplicateDeviceId_returns409() {
        UUID userId1 = createUser("+62812000010");
        UUID userId2 = createUser("+62812000011");
        String deviceId = DeviceIdTestSupport.randomDeviceId();

        adminPost("/admin/devices",
                new RegisterDeviceRequest(userId1, deviceId, "pk_dupdev_1_" + UUID.randomUUID(), "Device D1"),
                RegisterDeviceResponse.class);

        ResponseEntity<String> resp = rest.postForEntity(
                "/admin/devices",
                new HttpEntity<>(new RegisterDeviceRequest(userId2, deviceId, "pk_dupdev_2_" + UUID.randomUUID(), "Device D2"),
                        adminHeaders(testAdminJwt())),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // -------------------------------------------------------------------------
    // FR27/§1a: deviceId format validation — reject '/' and '|', accept a
    // non-UUID-shaped string
    // -------------------------------------------------------------------------

    @Test
    void registerDevice_deviceIdContainsSlash_returns400AndInsertsNoRow() {
        UUID userId = createUser("+62812000020");
        long countBefore = deviceRepository.count();

        ResponseEntity<String> resp = rest.postForEntity(
                "/admin/devices",
                new HttpEntity<>(new RegisterDeviceRequest(userId, "AA/BB1122CC", "pk_" + UUID.randomUUID(), "Bad Device"),
                        adminHeaders(testAdminJwt())),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(deviceRepository.count())
                .as("no device row should be inserted when deviceId contains '/'")
                .isEqualTo(countBefore);
    }

    @Test
    void registerDevice_deviceIdContainsPipe_returns400AndInsertsNoRow() {
        UUID userId = createUser("+62812000021");
        long countBefore = deviceRepository.count();

        ResponseEntity<String> resp = rest.postForEntity(
                "/admin/devices",
                new HttpEntity<>(new RegisterDeviceRequest(userId, "AA|BB1122CC", "pk_" + UUID.randomUUID(), "Bad Device"),
                        adminHeaders(testAdminJwt())),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(deviceRepository.count())
                .as("no device row should be inserted when deviceId contains '|'")
                .isEqualTo(countBefore);
    }

    @Test
    void registerDevice_nonUuidShapedDeviceId_returns201() {
        UUID userId = createUser("+62812000022");
        String deviceId = "A1B2C3D4E5F6"; // 12-char MAC-derived-style hex string, deliberately not UUID-shaped

        RegisterDeviceResponse resp = adminPost(
                "/admin/devices",
                new RegisterDeviceRequest(userId, deviceId, "pk_" + UUID.randomUUID(), "MAC-style Device"),
                RegisterDeviceResponse.class);

        assertThat(resp.deviceId()).isEqualTo(deviceId);
        assertThat(deviceRepository.findById(deviceId)).isPresent();
    }

    // -------------------------------------------------------------------------
    // DB-level backstop: device_id_no_forbidden_chars CHECK constraint (§1a/FR27)
    // Proves the DB layer independently rejects a forbidden character even if
    // application-level validation were somehow bypassed — belt-and-suspenders
    // alongside the 400s above, which go through the normal HTTP/validation path.
    // -------------------------------------------------------------------------

    @Test
    void deviceIdCheckConstraint_rejectsDirectInsertWithForbiddenChar() {
        UUID userId = createUser("+62812000030");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO devices (device_id, user_id, public_key, device_label, device_token_hash) " +
                "VALUES (?, ?, ?, 'Constraint Test Device', ?)",
                "BAD/DEVICE", userId, "pk_" + UUID.randomUUID(), "h" + UUID.randomUUID().toString().replace("-", "")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO devices (device_id, user_id, public_key, device_label, device_token_hash) " +
                "VALUES (?, ?, ?, 'Constraint Test Device', ?)",
                "BAD|DEVICE", userId, "pk_" + UUID.randomUUID(), "h" + UUID.randomUUID().toString().replace("-", "")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -------------------------------------------------------------------------
    // Invariant: max 3 devices per user (FR1 / Decision R3)
    // -------------------------------------------------------------------------

    @Test
    void registerDevice_fourthDevice_returns422() {
        UUID userId = createUser("+62812000004");

        for (int i = 1; i <= 3; i++) {
            adminPost("/admin/devices",
                    new RegisterDeviceRequest(userId, DeviceIdTestSupport.randomDeviceId(), "pk_user4_" + i, "Device " + i),
                    RegisterDeviceResponse.class);
        }

        ResponseEntity<String> resp = rest.postForEntity(
                "/admin/devices",
                new HttpEntity<>(new RegisterDeviceRequest(userId, DeviceIdTestSupport.randomDeviceId(), "pk_user4_4", "Device 4"), adminHeaders(testAdminJwt())),
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
