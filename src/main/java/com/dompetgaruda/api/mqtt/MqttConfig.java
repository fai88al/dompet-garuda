package com.dompetgaruda.api.mqtt;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Paho MQTT client for the worker profile.
 *
 * <p>Connects to the broker at startup. If the broker is unavailable, logs a WARNING and returns a
 * disconnected client — the worker continues to settle batches normally. Paho's automatic
 * reconnect will retry in the background with exponential back-off up to 60 s.
 *
 * <p>TLS: the broker URL uses the {@code ssl://} scheme. The default JVM trust store (cacerts)
 * contains ISRG Root X1, so Let's Encrypt certificates are trusted without any custom
 * {@code SSLSocketFactory} configuration.
 *
 * <p>{@code @Profile("worker")} — never loaded in the api profile (§3 profile isolation rule).
 */
@Configuration
@Profile("worker")
public class MqttConfig {

    private static final Logger log = LoggerFactory.getLogger(MqttConfig.class);

    /**
     * Creates and connects a Paho {@link MqttClient}.
     *
     * <p>QoS 1, clean-session false, auto-reconnect true (exponential back-off up to 60 s),
     * 10 s connection timeout. A failed initial connection is non-fatal: the client is returned
     * disconnected and will reconnect automatically.
     *
     * @throws MqttException if the client itself cannot be instantiated (e.g. malformed broker URL)
     *                       — treated as a fatal configuration error and prevents startup.
     */
    @Bean
    public MqttClient mqttClient(
            @Value("${mqtt.broker-url}") String brokerUrl,
            @Value("${mqtt.client-id}") String clientId,
            @Value("${mqtt.password:}") String password) throws MqttException {

        MqttClient client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());

        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setUserName("dompet-worker");
        opts.setPassword(password.toCharArray());
        opts.setCleanSession(false);
        opts.setAutomaticReconnect(true);
        opts.setMaxReconnectDelay(60_000);
        opts.setConnectionTimeout(10);

        try {
            client.connect(opts);
            log.info("MQTT connected to {}", brokerUrl);
        } catch (MqttException e) {
            log.warn("MQTT broker unavailable at startup ({}): {}. " +
                     "Settlement will continue; publisher will skip until reconnected.",
                    brokerUrl, e.getMessage());
        }

        return client;
    }
}
