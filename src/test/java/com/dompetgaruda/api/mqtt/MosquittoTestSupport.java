package com.dompetgaruda.api.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Shared test infrastructure for a real {@code eclipse-mosquitto:2} broker running the Dynamic
 * Security plugin, replicating the production setup in CLAUDE.md §15 (folder-mounted dynsec
 * state, correct ownership, {@code mosquitto_ctrl dynsec init} bootstrap) rather than assuming
 * any of it pre-exists in the image. Used by {@code ApiIntegrationTestBase} (a shared, always-up
 * broker for every api-profile test that touches {@code AdminService}) and by
 * {@code MqttProvisioningTest} (a dedicated, disposable broker for outage/lifecycle scenarios).
 */
public final class MosquittoTestSupport {

    private static final String COMMAND_TOPIC = "$CONTROL/dynamic-security/v1";
    private static final String RESPONSE_TOPIC = "$CONTROL/dynamic-security/v1/response";
    public static final String DEVICE_ROLE = "device-role";

    private MosquittoTestSupport() {}

    /**
     * Builds (but does not start) a Mosquitto container bootstrapped via
     * {@code mosquitto_ctrl dynsec init <file> <adminUser> <adminPassword>} — the standalone,
     * broker-not-required bootstrap command — followed by chown'ing the mounted dynsec folder to
     * {@code mosquitto:mosquitto} (the container's internal user; a single-file bind mount would
     * break Mosquitto's write-temp-then-rename save routine, so this must be a folder mount) and
     * finally exec'ing mosquitto itself. Every step runs as root via an overridden entrypoint,
     * since Mosquitto drops privilege to the {@code mosquitto} user internally on start.
     */
    public static GenericContainer<?> newContainer(String adminUsername, String adminPassword) {
        Path dynsecDir;
        try {
            dynsecDir = Files.createTempDirectory("dompet-dynsec-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        String bootstrapCommand =
                "mosquitto_ctrl dynsec init /mosquitto/dynsec/dynamic-security.json "
                        + adminUsername + " " + adminPassword
                        + " && chown -R mosquitto:mosquitto /mosquitto/dynsec"
                        + " && exec mosquitto -c /mosquitto/config/mosquitto.conf";

        return new GenericContainer<>("eclipse-mosquitto:2")
                .withExposedPorts(1883)
                .withFileSystemBind(dynsecDir.toAbsolutePath().toString(), "/mosquitto/dynsec", BindMode.READ_WRITE)
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("mosquitto-test/mosquitto.conf"),
                        "/mosquitto/config/mosquitto.conf")
                .withCreateContainerCmdModifier(cmd -> cmd
                        .withUser("root")
                        .withEntrypoint("sh", "-c", bootstrapCommand))
                .waitingFor(Wait.forListeningPort());
    }

    /** {@code tcp://host:mappedPort} broker URL for a started container. */
    public static String brokerUrl(GenericContainer<?> mosquitto) {
        return "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883);
    }

    /**
     * Creates the shared {@code device-role} role (publish + subscribe on {@code wallet/%u/#}) —
     * mirrors the role CLAUDE.md §15 says already exists on the production broker, but which does
     * NOT pre-exist in a fresh test container, so every test suite must create it itself.
     */
    public static void createDeviceRole(String brokerUrl, String adminUsername, String adminPassword) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode aclPublish = mapper.createObjectNode()
                .put("acltype", "publishClientSend").put("topic", "wallet/%u/#").put("allow", true);
        ObjectNode aclSubscribe = mapper.createObjectNode()
                .put("acltype", "subscribePattern").put("topic", "wallet/%u/#").put("allow", true);

        ObjectNode cmd = mapper.createObjectNode();
        cmd.put("command", "createRole");
        cmd.put("rolename", DEVICE_ROLE);
        cmd.putArray("acls").add(aclPublish).add(aclSubscribe);
        cmd.put("correlationData", "bootstrap-device-role");

        ObjectNode envelope = mapper.createObjectNode();
        envelope.putArray("commands").add(cmd);

        sendAdminCommand(brokerUrl, adminUsername, adminPassword, mapper.writeValueAsBytes(envelope));
    }

    private static void sendAdminCommand(String brokerUrl, String adminUsername, String adminPassword, byte[] payload)
            throws Exception {
        MqttClient client = new MqttClient(brokerUrl, "test-bootstrap-" + UUID.randomUUID(), new MemoryPersistence());
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setUserName(adminUsername);
        opts.setPassword(adminPassword.toCharArray());
        opts.setCleanSession(true);
        opts.setConnectionTimeout(10);

        BlockingQueue<JsonNode> responses = new LinkedBlockingQueue<>();
        ObjectMapper mapper = new ObjectMapper();

        try {
            client.connect(opts);
            client.subscribe(RESPONSE_TOPIC, 1, (topic, message) -> {
                JsonNode root = mapper.readTree(message.getPayload());
                for (JsonNode r : root.path("responses")) responses.add(r);
            });

            MqttMessage msg = new MqttMessage(payload);
            msg.setQos(1);
            client.publish(COMMAND_TOPIC, msg);

            JsonNode response = responses.poll(5, TimeUnit.SECONDS);
            if (response == null) {
                throw new IllegalStateException("Timed out waiting for dynamic-security bootstrap response");
            }
            if (response.has("error")) {
                throw new IllegalStateException("dynamic-security bootstrap command failed: " + response.get("error").asText());
            }
        } finally {
            if (client.isConnected()) client.disconnect();
            client.close();
        }
    }

    /** Attempts a real MQTT connection with the given credentials; returns whether it succeeded. */
    public static boolean canConnect(String brokerUrl, String username, String password) {
        MqttClient client = null;
        try {
            client = new MqttClient(brokerUrl, "test-probe-" + UUID.randomUUID(), new MemoryPersistence());
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setUserName(username);
            opts.setPassword(password.toCharArray());
            opts.setCleanSession(true);
            opts.setConnectionTimeout(5);
            client.connect(opts);
            return client.isConnected();
        } catch (MqttException e) {
            return false;
        } finally {
            try {
                if (client != null) {
                    if (client.isConnected()) client.disconnect();
                    client.close();
                }
            } catch (MqttException ignored) {
                // best-effort cleanup of the probe client
            }
        }
    }
}
