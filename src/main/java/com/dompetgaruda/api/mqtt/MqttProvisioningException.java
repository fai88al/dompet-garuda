package com.dompetgaruda.api.mqtt;

/**
 * Thrown when a Mosquitto Dynamic Security command issued by {@link MqttAdminClient} fails
 * or times out.
 *
 * <p>Unchecked so that {@code @Transactional} methods (e.g. device registration) roll back
 * on the default exception rules without needing {@code rollbackFor} (CLAUDE.md §15).
 */
public class MqttProvisioningException extends RuntimeException {

    public MqttProvisioningException(String message) {
        super(message);
    }

    public MqttProvisioningException(String message, Throwable cause) {
        super(message, cause);
    }
}
