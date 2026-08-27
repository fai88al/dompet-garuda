package com.dompetgaruda.api.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Administers per-device MQTT credentials via Mosquitto's Dynamic Security plugin
 * ({@code $CONTROL/dynamic-security/v1}).
 *
 * <p>{@code @Profile("api")} only — fully separate bean/connection from the worker's
 * {@link org.eclipse.paho.client.mqttv3.MqttClient} publisher (CLAUDE.md §15). Never merge
 * the two: this connects as {@code dompet-api-admin} (an {@code admin}-role dynsec account),
 * the worker connects as {@code dompet-worker} (a {@code worker-role} account).
 *
 * <p><b>Connection model:</b> one long-lived connection, opened once at bean construction and
 * reused for every command. Each call publishes a command to {@code $CONTROL/dynamic-security/v1}
 * tagged with a unique {@code correlationData} value and awaits the matching entry in the
 * {@code responses} array published back to {@code $CONTROL/dynamic-security/v1/response},
 * up to a 5 second timeout. This avoids paying a fresh TLS+MQTT handshake on every admin
 * action (device registration, suspend, reinstate) and mirrors the pattern the worker's
 * {@code MqttConfig} already uses for its own long-lived publisher connection. Paho's
 * automatic reconnect (same settings as the worker) keeps the connection alive across
 * transient broker restarts.
 *
 * <p>Never logs {@code deviceToken} or the admin password anywhere (CLAUDE.md §7 rule 9).
 */
@Component
@Profile("api")
public class MqttAdminClient {

    private static final Logger log = LoggerFactory.getLogger(MqttAdminClient.class);

    private static final String COMMAND_TOPIC = "$CONTROL/dynamic-security/v1";
    private static final String RESPONSE_TOPIC = "$CONTROL/dynamic-security/v1/response";
    private static final String DEVICE_ROLE = "device-role";
    private static final long RESPONSE_TIMEOUT_SECONDS = 5;

    private final ObjectMapper objectMapper;
    private final MqttClient client;
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    public MqttAdminClient(
            @Value("${mqtt.broker-url}") String brokerUrl,
            @Value("${mqtt.admin.username}") String username,
            @Value("${mqtt.admin.password}") String password,
            ObjectMapper objectMapper) throws MqttException {
        this.objectMapper = objectMapper;
        this.client = new MqttClient(brokerUrl, "dompet-api-admin-" + UUID.randomUUID(), new MemoryPersistence());

        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setUserName(username);
        opts.setPassword(password.toCharArray());
        opts.setCleanSession(true);
        opts.setAutomaticReconnect(true);
        opts.setMaxReconnectDelay(60_000);
        opts.setConnectionTimeout(10);

        client.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                log.warn("MqttAdminClient connection lost: {}", cause.getMessage());
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                handleResponse(message);
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // no-op: commands are matched via correlationData, not delivery tokens
            }
        });

        client.connect(opts);
        client.subscribe(RESPONSE_TOPIC, 1);
        log.info("MqttAdminClient connected to {}", brokerUrl);
    }

    /**
     * Provisions MQTT credentials for a newly registered device: creates the dynsec client
     * (username={@code deviceId}, password={@code deviceToken} — the same device token already
     * generated at registration, no new secret) and attaches it to the shared {@code device-role}
     * role. Both steps must succeed; either failure throws and leaves no partial dynsec state
     * relied upon (a create without a role attached would leave the client unable to publish
     * or subscribe under {@code wallet/{deviceId}/#}, so the caller must treat this as one
     * atomic provisioning step and roll back the device registration on failure).
     */
    public void provisionDevice(String deviceId, String deviceToken) {
        sendCommand("createClient", Map.of("username", deviceId, "password", deviceToken));
        sendCommand("addClientRole", Map.of("username", deviceId, "rolename", DEVICE_ROLE));
        log.info("Provisioned MQTT credentials for device {}", deviceId);
    }

    /** Disables the device's dynsec client, blocking any further MQTT connection (FR26). */
    public void revokeDevice(String deviceId) {
        sendCommand("disableClient", Map.of("username", deviceId));
        log.info("Revoked MQTT access for device {}", deviceId);
    }

    /** Re-enables the device's dynsec client, restoring its MQTT connection (FR26). */
    public void reinstateDevice(String deviceId) {
        sendCommand("enableClient", Map.of("username", deviceId));
        log.info("Reinstated MQTT access for device {}", deviceId);
    }

    private JsonNode sendCommand(String command, Map<String, String> params) {
        String correlationData = UUID.randomUUID().toString();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(correlationData, future);

        try {
            ObjectNode cmd = objectMapper.createObjectNode();
            cmd.put("command", command);
            params.forEach(cmd::put);
            cmd.put("correlationData", correlationData);

            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.putArray("commands").add(cmd);

            MqttMessage msg = new MqttMessage(objectMapper.writeValueAsBytes(envelope));
            msg.setQos(1);
            client.publish(COMMAND_TOPIC, msg);

            JsonNode response = future.get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (response.has("error")) {
                throw new MqttProvisioningException(
                        "dynamic-security command '" + command + "' failed: " + response.get("error").asText());
            }
            return response;
        } catch (TimeoutException e) {
            throw new MqttProvisioningException(
                    "Timed out waiting for dynamic-security response to '" + command + "'", e);
        } catch (MqttProvisioningException e) {
            throw e;
        } catch (Exception e) {
            throw new MqttProvisioningException(
                    "Failed to send dynamic-security command '" + command + "'", e);
        } finally {
            pending.remove(correlationData);
        }
    }

    private void handleResponse(MqttMessage message) {
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            for (JsonNode response : root.path("responses")) {
                String correlationData = response.path("correlationData").asText(null);
                if (correlationData == null) continue;
                CompletableFuture<JsonNode> future = pending.get(correlationData);
                if (future != null) future.complete(response);
            }
        } catch (Exception e) {
            log.warn("Failed to parse dynamic-security response: {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        try {
            if (client.isConnected()) client.disconnect();
            client.close();
        } catch (MqttException e) {
            log.warn("Error closing MqttAdminClient: {}", e.getMessage());
        }
    }
}
