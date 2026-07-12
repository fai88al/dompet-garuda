package com.dompetgaruda.api.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fire-and-forget MQTT publisher for the worker profile.
 *
 * <p>Both methods catch ALL exceptions and log a WARNING — they must never throw or
 * interrupt settlement (§7 invariant 8: MQTT carries no financial authority).
 *
 * <p>{@code @Profile("worker")} — never loaded in the api profile.
 */
@Service
@Profile("worker")
public class MqttPublisherService {

    private static final Logger log = LoggerFactory.getLogger(MqttPublisherService.class);
    private static final int QOS_1 = 1;

    private final MqttClient client;
    private final ObjectMapper objectMapper;

    public MqttPublisherService(MqttClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    /**
     * Publishes settlement outcome to {@code wallet/{deviceId}/sync-result}.
     *
     * @param status {@code "SETTLED"} or {@code "FAILED"}
     * @param detail optional human-readable detail; may be {@code null}
     */
    public void publishSyncResult(String deviceId, String batchId, String status, String detail) {
        try {
            if (!client.isConnected()) {
                log.warn("MQTT not connected — skipping sync-result publish for device {} batch {}",
                        deviceId, batchId);
                return;
            }
            String topic = "wallet/" + deviceId + "/sync-result";
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("batchId", batchId);
            payload.put("status", status);
            if (detail != null) payload.put("detail", detail);
            MqttMessage msg = new MqttMessage(objectMapper.writeValueAsBytes(payload));
            msg.setQos(QOS_1);
            msg.setRetained(false);
            client.publish(topic, msg);
            log.debug("Published sync-result ({}) for device {} batch {}", status, deviceId, batchId);
        } catch (Exception e) {
            log.warn("Failed to publish sync-result for device {} batch {}: {}", deviceId, batchId, e.getMessage());
        }
    }

    /**
     * Publishes a cert-refresh hint to {@code wallet/{deviceId}/cert-refresh}.
     *
     * <p>The device fetches the new certificate over HTTPS after receiving this hint.
     */
    public void publishCertRefresh(String deviceId) {
        try {
            if (!client.isConnected()) {
                log.warn("MQTT not connected — skipping cert-refresh hint for device {}", deviceId);
                return;
            }
            String topic = "wallet/" + deviceId + "/cert-refresh";
            MqttMessage msg = new MqttMessage(
                    "{\"refresh\":true}".getBytes(StandardCharsets.UTF_8));
            msg.setQos(QOS_1);
            msg.setRetained(false);
            client.publish(topic, msg);
            log.debug("Published cert-refresh hint for device {}", deviceId);
        } catch (Exception e) {
            log.warn("Failed to publish cert-refresh hint for device {}: {}", deviceId, e.getMessage());
        }
    }
}
