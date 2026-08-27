package com.dompetgaruda.api.mqtt;

import com.dompetgaruda.api.ApiIntegrationTestBase;
import com.dompetgaruda.api.common.repository.DeviceRepository;
import com.dompetgaruda.api.device.dto.CreateUserRequest;
import com.dompetgaruda.api.device.dto.CreateUserResponse;
import com.dompetgaruda.api.device.dto.RegisterDeviceRequest;
import com.dompetgaruda.api.device.dto.RegisterDeviceResponse;
import com.dompetgaruda.api.device.dto.UpdateDeviceStatusRequest;
import com.dompetgaruda.api.device.dto.UpdateDeviceStatusResponse;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for FR25/FR26 — MQTT per-device provisioning via {@link MqttAdminClient},
 * against a REAL {@code eclipse-mosquitto:2} broker running the Dynamic Security plugin
 * (CLAUDE.md §15), not a mock.
 *
 * <p>Extends {@link ApiIntegrationTestBase} to reuse its shared Postgres container and admin JWT
 * helpers, but overrides {@code mqtt.*} with its OWN dedicated Mosquitto container (started here,
 * not the base class's always-up shared broker) so these tests can pause/unpause the broker to
 * simulate an outage without disrupting every other api-profile test class, which also depends
 * on a working {@code MqttAdminClient} bean via the shared broker.
 *
 * <p>Test methods are deliberately ordered: {@link #registerDevice_brokerUnreachable_returns503AndInsertsNoRow()}
 * pauses the dedicated broker and unpauses it again before returning, so later tests still see a
 * working broker. The container is paused (not stopped), which keeps its Docker port mapping
 * stable across the outage — a bare stop/start on Testcontainers can reassign a new random host
 * port, breaking the already-connected {@code MqttAdminClient} bean living in this test's cached
 * Spring context.
 *
 * <p>The dedicated container is wired in via {@link ApiIntegrationTestBase}'s
 * {@code mqttContainerOverride}/{@code mqttAdminUsernameOverride}/{@code mqttAdminPasswordOverride}
 * fields, set in a static initializer below, rather than via this subclass's own
 * {@code @DynamicPropertySource} method — empirically, a subclass's own such method is NOT
 * reliably given precedence over the base class's, so the app ended up connected to the base's
 * shared broker instead of this class's dedicated one when that was tried first.
 *
 * <p>{@code @TestPropertySource} adds a throwaway marker property purely so Spring's test
 * context cache key differs from every other {@code ApiIntegrationTestBase} subclass. Without
 * it, Spring sees an IDENTICAL configuration (same inherited {@code @DynamicPropertySource}
 * method, same annotations) across every subclass and — since dynamic property VALUES are not
 * part of that cache key, only the customizer/method identity is — happily reuses whichever
 * subclass's context happened to be built first, complete with its already-connected
 * {@code MqttAdminClient} pointed at the SHARED broker. This marker forces a genuinely separate,
 * freshly-built context for this class, so {@code baseProps()} re-evaluates against the override
 * fields set below rather than an already-cached bean from an unrelated test class.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = "mqtt.provisioning.test.context-marker=true")
class MqttProvisioningTest extends ApiIntegrationTestBase {

    private static final String ADMIN_USERNAME = "dompet-api-admin-test";
    private static final String ADMIN_PASSWORD = "test-provisioning-admin-pw";

    @SuppressWarnings("resource")
    private static final GenericContainer<?> dedicatedMosquitto = startDedicated();

    static {
        mqttContainerOverride = dedicatedMosquitto;
        mqttAdminUsernameOverride = ADMIN_USERNAME;
        mqttAdminPasswordOverride = ADMIN_PASSWORD;
    }

    private static GenericContainer<?> startDedicated() {
        GenericContainer<?> c = MosquittoTestSupport.newContainer(ADMIN_USERNAME, ADMIN_PASSWORD);
        c.start();
        try {
            MosquittoTestSupport.createDeviceRole(MosquittoTestSupport.brokerUrl(c), ADMIN_USERNAME, ADMIN_PASSWORD);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to bootstrap dedicated test Mosquitto broker", e);
        }
        return c;
    }

    @Autowired TestRestTemplate rest;
    @Autowired DeviceRepository deviceRepository;

    @Test
    @Order(1)
    void registerDevice_happyPath_deviceCanConnectWithReturnedToken() {
        UUID userId = createUser("+62870000001");
        RegisterDeviceResponse reg = registerDevice(userId, "pk-mqtt-001");

        boolean connected = MosquittoTestSupport.canConnect(
                MosquittoTestSupport.brokerUrl(dedicatedMosquitto),
                reg.deviceId().toString(),
                reg.deviceToken());

        assertThat(connected)
                .as("device should be able to connect to MQTT using its registration token as password")
                .isTrue();
    }

    @Test
    @Order(2)
    void registerDevice_brokerUnreachable_returns503AndInsertsNoRow() {
        UUID userId = createUser("+62870000002");
        long deviceCountBefore = deviceRepository.count();

        pauseBroker();
        try {
            ResponseEntity<String> resp = rest.postForEntity(
                    "/admin/devices",
                    new HttpEntity<>(new RegisterDeviceRequest(userId, "pk-mqtt-002", "Outage Device"), adminHeaders()),
                    String.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(deviceRepository.count())
                    .as("no device row should be inserted when MQTT provisioning fails")
                    .isEqualTo(deviceCountBefore);
        } finally {
            unpauseBroker();
        }
    }

    @Test
    @Order(3)
    void suspendStatus_blocksMqttConnection() {
        UUID userId = createUser("+62870000003");
        RegisterDeviceResponse reg = registerDevice(userId, "pk-mqtt-003");

        patchStatus(reg.deviceId(), "SUSPENDED");

        boolean connected = MosquittoTestSupport.canConnect(
                MosquittoTestSupport.brokerUrl(dedicatedMosquitto), reg.deviceId().toString(), reg.deviceToken());
        assertThat(connected).as("suspended device should be rejected by the broker").isFalse();
    }

    @Test
    @Order(4)
    void reinstateStatus_restoresMqttConnection() {
        UUID userId = createUser("+62870000004");
        RegisterDeviceResponse reg = registerDevice(userId, "pk-mqtt-004");
        patchStatus(reg.deviceId(), "SUSPENDED");

        patchStatus(reg.deviceId(), "ACTIVE");

        boolean connected = MosquittoTestSupport.canConnect(
                MosquittoTestSupport.brokerUrl(dedicatedMosquitto), reg.deviceId().toString(), reg.deviceToken());
        assertThat(connected).as("reinstated device should be able to connect again").isTrue();
    }

    @Test
    @Order(5)
    void statusEndpoint_stillReturns200AndUpdatesDb_whenBrokerUnreachable() {
        UUID userId = createUser("+62870000005");
        RegisterDeviceResponse reg = registerDevice(userId, "pk-mqtt-005");

        pauseBroker();
        try {
            ResponseEntity<UpdateDeviceStatusResponse> resp = rest.exchange(
                    "/admin/devices/" + reg.deviceId() + "/status",
                    HttpMethod.PATCH,
                    new HttpEntity<>(new UpdateDeviceStatusRequest("SUSPENDED"), adminHeaders()),
                    UpdateDeviceStatusResponse.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().status()).isEqualTo("SUSPENDED");
            assertThat(deviceRepository.findById(reg.deviceId()).orElseThrow().getStatus())
                    .isEqualTo("SUSPENDED");
        } finally {
            unpauseBroker();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void pauseBroker() {
        DockerClientFactory.instance().client().pauseContainerCmd(dedicatedMosquitto.getContainerId()).exec();
    }

    private static void unpauseBroker() {
        DockerClientFactory.instance().client().unpauseContainerCmd(dedicatedMosquitto.getContainerId()).exec();
    }

    private UUID createUser(String phone) {
        ResponseEntity<CreateUserResponse> resp = rest.postForEntity(
                "/admin/users",
                new HttpEntity<>(new CreateUserRequest("Test User", phone), adminHeaders()),
                CreateUserResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody().userId();
    }

    /**
     * Registers a device, retrying briefly on 503. A prior test in this class may have just
     * paused/unpaused the dedicated broker (simulating an outage) — Paho's automatic reconnect
     * needs a moment to restore {@code MqttAdminClient}'s connection afterward, during which a
     * fresh registration can transiently 503. This mirrors how a real caller would retry, and
     * keeps this test suite from being flaky about exactly how long that reconnect takes.
     */
    private RegisterDeviceResponse registerDevice(UUID userId, String pubKey) {
        ResponseEntity<RegisterDeviceResponse> resp;
        long deadline = System.currentTimeMillis() + 45_000;
        while (true) {
            resp = rest.postForEntity(
                    "/admin/devices",
                    new HttpEntity<>(new RegisterDeviceRequest(userId, pubKey, "MQTT Test Device"), adminHeaders()),
                    RegisterDeviceResponse.class);
            if (resp.getStatusCode() == HttpStatus.CREATED || System.currentTimeMillis() >= deadline) break;
            try { Thread.sleep(200); } catch (InterruptedException ignored) { break; }
        }
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    private void patchStatus(UUID deviceId, String status) {
        ResponseEntity<UpdateDeviceStatusResponse> resp = rest.exchange(
                "/admin/devices/" + deviceId + "/status",
                HttpMethod.PATCH,
                new HttpEntity<>(new UpdateDeviceStatusRequest(status), adminHeaders()),
                UpdateDeviceStatusResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(testAdminJwt());
        return h;
    }
}
