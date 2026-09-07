package com.dompetgaruda.api.notification;

import com.dompetgaruda.api.mqtt.MqttAdminClient;
import com.dompetgaruda.api.mqtt.MqttPublisherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 3 Feature A — Notification Reconciliation for offline transfers (CLAUDE.md §17).
 *
 * <p>Tracks {@code wallet/{deviceId}/payment-received} delivery for OFFLINE_TRANSFER
 * settlements in {@code notification_log}. This is delivery metadata only — it never gates
 * or reflects money correctness (§7 invariant 8); the ledger remains authoritative regardless
 * of any row's status here. {@code GET /device/balance} must never reference this table.
 *
 * <p>Profile-agnostic (like {@link com.dompetgaruda.api.sync.SyncSettlementService}): the
 * worker calls {@link #recordAndNotify} right after posting an OFFLINE_TRANSFER, using the
 * real {@link MqttPublisherService}; api-profile device endpoints call
 * {@link #reconcileForDevice} on any authenticated hit (no new "I'm online now" endpoint),
 * using {@link MqttAdminClient}'s existing connection since the worker's publisher bean does
 * not exist in that profile. Both publish paths are best-effort and never throw.
 */
@Service
public class NotificationReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationReconciliationService.class);

    private final JdbcTemplate jdbc;
    private final int expiryDays;

    // Exactly one of these is non-null depending on active profile — see class javadoc.
    @Autowired(required = false)
    private MqttPublisherService workerPublisher;
    @Autowired(required = false)
    private MqttAdminClient apiPublisher;

    public NotificationReconciliationService(JdbcTemplate jdbc,
            @Value("${notification.reconciliation.expiry-days}") int expiryDays) {
        this.jdbc = jdbc;
        this.expiryDays = expiryDays;
    }

    /**
     * Records a PENDING notification for an OFFLINE_TRANSFER receiver and attempts an
     * immediate publish. Called by the worker right after the OFFLINE_TRANSFER ledger posting
     * commits (§17 point 3). Never throws — settlement must complete regardless of MQTT state.
     */
    public void recordAndNotify(UUID offlineTxnId, String receiverDeviceId, long transactionId) {
        try {
            long id = jdbc.queryForObject(
                    "INSERT INTO notification_log (offline_transaction_id, device_id, status, expires_at) " +
                    "VALUES (?, ?, 'PENDING', now() + (? || ' days')::interval) RETURNING id",
                    Long.class, offlineTxnId, receiverDeviceId, expiryDays);

            if (publish(receiverDeviceId, transactionId)) {
                markDelivered(id);
            }
        } catch (Exception e) {
            log.warn("Failed to record/notify for offline txn {} device {}: {}",
                    offlineTxnId, receiverDeviceId, e.getMessage());
        }
    }

    /**
     * Re-publishes every PENDING notification for a device, marking each DELIVERED on
     * confirmed publish (§17 point 4). Called from device-facing endpoints when a device makes
     * any authenticated call — a reconnect proxy, not a real presence signal. Never throws.
     */
    public void reconcileForDevice(String deviceId) {
        try {
            List<Map<String, Object>> pending = jdbc.queryForList(
                    "SELECT nl.id, lt.transaction_id " +
                    "FROM notification_log nl " +
                    "JOIN ledger_transactions lt " +
                    "  ON lt.reference_type = 'OFFLINE_TXN' " +
                    " AND lt.reference_id = nl.offline_transaction_id::text " +
                    "WHERE nl.device_id = ? AND nl.status = 'PENDING'",
                    deviceId);

            for (Map<String, Object> row : pending) {
                long id = ((Number) row.get("id")).longValue();
                long transactionId = ((Number) row.get("transaction_id")).longValue();
                if (publish(deviceId, transactionId)) {
                    markDelivered(id);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to reconcile notifications for device {}: {}", deviceId, e.getMessage());
        }
    }

    private boolean publish(String deviceId, long transactionId) {
        if (workerPublisher != null) return workerPublisher.publishPaymentReceived(deviceId, transactionId);
        if (apiPublisher != null) return apiPublisher.publishPaymentReceivedBestEffort(deviceId, transactionId);
        return false;
    }

    private void markDelivered(long id) {
        jdbc.update("UPDATE notification_log SET status = 'DELIVERED', delivered_at = now() WHERE id = ?", id);
    }
}
