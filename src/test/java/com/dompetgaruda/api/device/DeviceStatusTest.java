package com.dompetgaruda.api.device;

import com.dompetgaruda.api.ApiIntegrationTestBase;
import com.dompetgaruda.api.device.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for FR17 — PATCH /admin/devices/{deviceId}/status.
 *
 * <p>Cases covered:
 * <ol>
 *   <li>Update status to SUSPENDED — returns 200 with new status and updatedAt timestamp.</li>
 *   <li>Suspended device fails Bearer token verification on a device endpoint (401).</li>
 *   <li>Invalid status value (not in ACTIVE/SUSPENDED/LOCKED) — returns 400.</li>
 *   <li>Unknown deviceId — returns 404.</li>
 * </ol>
 */
class DeviceStatusTest extends ApiIntegrationTestBase {

    private static final String ADMIN_TOKEN = "test-admin-device-status-fr17";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("admin.api-token", () -> ADMIN_TOKEN);
    }

    @Autowired TestRestTemplate rest;

    @Test
    void updateStatus_toSuspended_returns200() {
        UUID userId = createUser("+62831000001");
        UUID deviceId = registerDevice(userId, "pk-status-001").deviceId();

        ResponseEntity<UpdateDeviceStatusResponse> resp = patchStatus(deviceId, "SUSPENDED",
                UpdateDeviceStatusResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().deviceId()).isEqualTo(deviceId);
        assertThat(resp.getBody().status()).isEqualTo("SUSPENDED");
        assertThat(resp.getBody().updatedAt()).isNotNull();
    }

    @Test
    void suspendedDevice_failsDeviceAuth_returns401() {
        UUID userId = createUser("+62831000002");
        RegisterDeviceResponse reg = registerDevice(userId, "pk-status-002");

        // Suspend the device
        patchStatus(reg.deviceId(), "SUSPENDED", UpdateDeviceStatusResponse.class);

        // Verify the device's token is now rejected on a device-auth endpoint
        ResponseEntity<String> balanceResp = rest.exchange(
                "/device/balance",
                HttpMethod.GET,
                new HttpEntity<>(deviceHeaders(reg.deviceToken())),
                String.class);
        assertThat(balanceResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void updateStatus_invalidValue_returns400() {
        UUID userId = createUser("+62831000003");
        UUID deviceId = registerDevice(userId, "pk-status-003").deviceId();

        ResponseEntity<String> resp = patchStatus(deviceId, "INVALID_STATUS", String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updateStatus_unknownDevice_returns404() {
        ResponseEntity<String> resp = patchStatus(UUID.randomUUID(), "SUSPENDED", String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UUID createUser(String phone) {
        ResponseEntity<CreateUserResponse> resp = rest.postForEntity(
                "/admin/users",
                new HttpEntity<>(new CreateUserRequest("Test User", phone), adminHeaders()),
                CreateUserResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody().userId();
    }

    private RegisterDeviceResponse registerDevice(UUID userId, String pubKey) {
        ResponseEntity<RegisterDeviceResponse> resp = rest.postForEntity(
                "/admin/devices",
                new HttpEntity<>(new RegisterDeviceRequest(userId, pubKey, "Test Device"), adminHeaders()),
                RegisterDeviceResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    private <T> ResponseEntity<T> patchStatus(UUID deviceId, String status, Class<T> responseType) {
        return rest.exchange(
                "/admin/devices/" + deviceId + "/status",
                HttpMethod.PATCH,
                new HttpEntity<>(new UpdateDeviceStatusRequest(status), adminHeaders()),
                responseType);
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(ADMIN_TOKEN);
        return h;
    }

    private HttpHeaders deviceHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return h;
    }
}
