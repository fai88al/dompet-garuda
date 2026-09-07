package com.dompetgaruda.api.sync;

import com.dompetgaruda.api.DeviceIdTestSupport;
import com.dompetgaruda.api.WorkerIntegrationTestBase;
import com.dompetgaruda.api.ledger.LedgerEntry;
import com.dompetgaruda.api.ledger.LedgerPostingService;
import com.dompetgaruda.api.ledger.PostingRequest;
import com.dompetgaruda.api.mqtt.MqttPublisherService;
import com.dompetgaruda.api.notification.NotificationReconciliationService;
import com.dompetgaruda.api.sync.dto.SyncBatchRequest;
import com.dompetgaruda.api.sync.dto.SyncOfflineTxnRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 Feature A — Notification Reconciliation (CLAUDE.md §17). Covers 5 of the 7 signed
 * acceptance criteria: online receiver, offline-then-reconnect receiver, idempotency replay,
 * worker retry, no double-send. The other two live elsewhere — expiry-after-3-days is
 * {@code com.dompetgaruda.api.notification.NotificationExpiryJobTest} (doesn't need real
 * settlement), and balance correctness under total MQTT failure is
 * {@link NotificationBalanceCorrectnessTest} (needs a real HTTP {@code GET /device/balance}
 * call, which requires the api profile).
 *
 * <p>Drives real settlement via {@link SyncInboxPoller#processOneRow()} (mirrors
 * {@code SettlementTest}) with a Mockito mock in place of {@link MqttPublisherService}, so each
 * scenario controls exactly whether the "device" is reachable over MQTT at that moment.
 */
class NotificationReconciliationSettlementTest extends WorkerIntegrationTestBase {

    private static final KeyPair SENDER_KEYS;
    private static final KeyPair RECEIVER_KEYS;

    static {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
            SENDER_KEYS   = gen.generateKeyPair();
            RECEIVER_KEYS = gen.generateKeyPair();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final long ISSUED_AMOUNT   = 100_000L;
    private static final long TRANSFER_AMOUNT = 40_000L;

    @Autowired SyncInboxPoller                    poller;
    @Autowired JdbcTemplate                       jdbc;
    @Autowired LedgerPostingService                ledger;
    @Autowired ObjectMapper                        objectMapper;
    @Autowired NotificationReconciliationService   notificationService;
    @MockitoBean MqttPublisherService              mqttPublisher;

    private UUID senderUserId, senderOnlineAccountId, senderPouchAccountId;
    private UUID receiverUserId, receiverOnlineAccountId, receiverPouchAccountId;
    private String senderDeviceId, receiverDeviceId;

    @BeforeEach
    void setup() {
        senderUserId   = insertUser("Sender");
        receiverUserId = insertUser("Receiver");

        senderDeviceId   = insertDevice(senderUserId,   base64PublicKey(SENDER_KEYS));
        receiverDeviceId = insertDevice(receiverUserId, base64PublicKey(RECEIVER_KEYS));

        senderOnlineAccountId   = insertAccount(senderUserId,   null,           "ONLINE");
        senderPouchAccountId    = insertAccount(senderUserId,   senderDeviceId, "POUCH");
        receiverOnlineAccountId = insertAccount(receiverUserId, null,           "ONLINE");
        receiverPouchAccountId  = insertAccount(receiverUserId, receiverDeviceId, "POUCH");

        ledger.post(new PostingRequest("TOPUP", "TEST", "setup", "Test top-up",
                List.of(
                        new LedgerEntry(LedgerPostingService.SYSTEM_ACCOUNT_ID, "DEBIT",  ISSUED_AMOUNT),
                        new LedgerEntry(senderOnlineAccountId,                  "CREDIT", ISSUED_AMOUNT)
                )));
        ledger.post(new PostingRequest("POUCH_LOAD", "TEST", "setup", "Test pouch load",
                List.of(
                        new LedgerEntry(senderOnlineAccountId, "DEBIT",  ISSUED_AMOUNT),
                        new LedgerEntry(senderPouchAccountId,  "CREDIT", ISSUED_AMOUNT)
                )));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM notification_log WHERE device_id = ?", receiverDeviceId);
        jdbc.update("DELETE FROM offline_transactions WHERE sender_device_id = ? OR receiver_device_id = ?",
                senderDeviceId, receiverDeviceId);
        jdbc.update("DELETE FROM sync_inbox WHERE device_id = ?", senderDeviceId);
        jdbc.update(
                "DELETE FROM ledger_entries WHERE transaction_id IN (" +
                "  SELECT DISTINCT transaction_id FROM ledger_entries " +
                "  WHERE account_id IN (?, ?, ?, ?)" +
                ")",
                senderOnlineAccountId, senderPouchAccountId,
                receiverOnlineAccountId, receiverPouchAccountId);
        jdbc.update(
                "DELETE FROM ledger_transactions WHERE transaction_id NOT IN " +
                "(SELECT transaction_id FROM ledger_entries)");
        jdbc.update("DELETE FROM offline_certificates WHERE device_id = ?", senderDeviceId);
        jdbc.update("DELETE FROM accounts WHERE account_id IN (?, ?, ?, ?)",
                senderOnlineAccountId, senderPouchAccountId,
                receiverOnlineAccountId, receiverPouchAccountId);
        jdbc.update("DELETE FROM devices WHERE device_id IN (?, ?)", senderDeviceId, receiverDeviceId);
        jdbc.update("DELETE FROM users WHERE user_id IN (?, ?)", senderUserId, receiverUserId);
    }

    // -------------------------------------------------------------------------
    // AC 1 — online receiver: publish succeeds immediately at settlement time
    // -------------------------------------------------------------------------

    @Test
    void onlineReceiver_settlementPublishesImmediately_marksDelivered() throws Exception {
        when(mqttPublisher.publishPaymentReceived(eq(receiverDeviceId), anyLong())).thenReturn(true);

        UUID txnId = settleOneTransfer();

        assertThat(notificationStatus(txnId)).isEqualTo("DELIVERED");
        assertThat(notificationDeliveredAt(txnId)).isNotNull();
    }

    // -------------------------------------------------------------------------
    // AC 2 — offline-then-reconnect receiver
    // -------------------------------------------------------------------------

    @Test
    void offlineReceiver_settlementInsertsPending_publishFails_staysPending() throws Exception {
        when(mqttPublisher.publishPaymentReceived(eq(receiverDeviceId), anyLong())).thenReturn(false);

        UUID txnId = settleOneTransfer();

        assertThat(notificationStatus(txnId)).isEqualTo("PENDING");
        assertThat(notificationDeliveredAt(txnId)).isNull();
    }

    @Test
    void reconnectAfterOffline_reconcileForDevice_marksDelivered() throws Exception {
        when(mqttPublisher.publishPaymentReceived(eq(receiverDeviceId), anyLong())).thenReturn(false);
        UUID txnId = settleOneTransfer();
        assertThat(notificationStatus(txnId)).isEqualTo("PENDING");

        // Device reconnects — this time the publish succeeds.
        when(mqttPublisher.publishPaymentReceived(eq(receiverDeviceId), anyLong())).thenReturn(true);
        notificationService.reconcileForDevice(receiverDeviceId);

        assertThat(notificationStatus(txnId)).isEqualTo("DELIVERED");
        assertThat(notificationDeliveredAt(txnId)).isNotNull();
    }

    // -------------------------------------------------------------------------
    // AC 3 — idempotency replay / AC 5 — no double-send
    // -------------------------------------------------------------------------

    @Test
    void noDoubleSend_reconcileAfterDelivered_doesNotRepublish() throws Exception {
        when(mqttPublisher.publishPaymentReceived(eq(receiverDeviceId), anyLong())).thenReturn(true);
        UUID txnId = settleOneTransfer();
        assertThat(notificationStatus(txnId)).isEqualTo("DELIVERED");

        // Device hits another authenticated endpoint later — already DELIVERED, must not republish.
        notificationService.reconcileForDevice(receiverDeviceId);
        notificationService.reconcileForDevice(receiverDeviceId);

        verify(mqttPublisher, times(1)).publishPaymentReceived(eq(receiverDeviceId), anyLong());
        assertThat(notificationStatus(txnId)).isEqualTo("DELIVERED");
    }

    // -------------------------------------------------------------------------
    // AC 4 — worker retry: repeated reconnect attempts eventually deliver
    // -------------------------------------------------------------------------

    @Test
    void workerRetry_repeatedFailuresThenSuccess_eventuallyDelivered() throws Exception {
        when(mqttPublisher.publishPaymentReceived(eq(receiverDeviceId), anyLong())).thenReturn(false);
        UUID txnId = settleOneTransfer();

        notificationService.reconcileForDevice(receiverDeviceId);
        assertThat(notificationStatus(txnId)).isEqualTo("PENDING");

        notificationService.reconcileForDevice(receiverDeviceId);
        assertThat(notificationStatus(txnId)).isEqualTo("PENDING");

        when(mqttPublisher.publishPaymentReceived(eq(receiverDeviceId), anyLong())).thenReturn(true);
        notificationService.reconcileForDevice(receiverDeviceId);

        assertThat(notificationStatus(txnId)).isEqualTo("DELIVERED");
        // 1 attempt at settlement + 2 failed retries + 1 successful retry = 4 total attempts.
        verify(mqttPublisher, times(4)).publishPaymentReceived(eq(receiverDeviceId), anyLong());
    }

    // -------------------------------------------------------------------------
    // Shared settlement helper
    // -------------------------------------------------------------------------

    /** Settles one signed OFFLINE_TRANSFER from sender to receiver; returns its offlineTxnId. */
    private UUID settleOneTransfer() throws Exception {
        UUID certId  = insertCert(senderDeviceId, senderPouchAccountId, ISSUED_AMOUNT, Instant.now().plusSeconds(86_400));
        UUID batchId = UUID.randomUUID();
        UUID txnId   = UUID.randomUUID();
        Instant ts   = Instant.now();

        String msg = SyncSettlementService.buildSigningMessage(
                new SyncOfflineTxnRequest(txnId, receiverDeviceId, TRANSFER_AMOUNT, 1L, ts, null, null, null),
                senderDeviceId);
        SyncOfflineTxnRequest txn = new SyncOfflineTxnRequest(
                txnId, receiverDeviceId, TRANSFER_AMOUNT, 1L, ts,
                sign(msg, SENDER_KEYS), sign(msg, RECEIVER_KEYS), null);

        String json = objectMapper.writeValueAsString(new SyncBatchRequest(certId, List.of(txn)));
        jdbc.update(
                "INSERT INTO sync_inbox (batch_id, device_id, raw_payload, status, synced_after_expiry) " +
                "VALUES (?, ?, ?::jsonb, 'PENDING', false)",
                batchId, senderDeviceId, json);

        poller.processOneRow();
        assertThat(batchStatus(batchId)).isEqualTo("DONE");
        return txnId;
    }

    // -------------------------------------------------------------------------
    // Signing helpers
    // -------------------------------------------------------------------------

    private static String base64PublicKey(KeyPair kp) {
        return Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
    }

    private static String sign(String message, KeyPair kp) throws Exception {
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(kp.getPrivate());
        sig.update(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    // -------------------------------------------------------------------------
    // DB setup / assertion helpers
    // -------------------------------------------------------------------------

    private UUID insertUser(String label) {
        UUID id = UUID.randomUUID();
        String phone = "+62801" + System.nanoTime() % 100_000_000L;
        jdbc.update("INSERT INTO users (user_id, full_name, phone) VALUES (?, ?, ?)",
                id, label + " Notif Test User", phone);
        return id;
    }

    private String insertDevice(UUID userId, String pubKeyBase64) {
        String id      = DeviceIdTestSupport.randomDeviceId();
        String tokHash = UUID.randomUUID().toString().replace("-", "")
                       + UUID.randomUUID().toString().replace("-", "");
        jdbc.update(
                "INSERT INTO devices (device_id, user_id, public_key, device_label, device_token_hash) " +
                "VALUES (?, ?, ?, 'Notif Test Device', ?)",
                id, userId, pubKeyBase64, tokHash);
        return id;
    }

    private UUID insertAccount(UUID userId, String deviceId, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO accounts (account_id, user_id, device_id, type) VALUES (?, ?, ?, ?)",
                id, userId, deviceId, type);
        return id;
    }

    private UUID insertCert(String deviceId, UUID pouchAccountId, long issuedAmount, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO offline_certificates " +
                "(certificate_id, device_id, pouch_account_id, issued_amount, server_signature, status, expires_at) " +
                "VALUES (?, ?, ?, ?, 'test-sig', 'ACTIVE', ?)",
                id, deviceId, pouchAccountId, issuedAmount, Timestamp.from(expiresAt));
        return id;
    }

    private String batchStatus(UUID batchId) {
        return jdbc.queryForObject("SELECT status FROM sync_inbox WHERE batch_id = ?", String.class, batchId);
    }

    private String notificationStatus(UUID offlineTxnId) {
        return jdbc.queryForObject(
                "SELECT status FROM notification_log WHERE offline_transaction_id = ?", String.class, offlineTxnId);
    }

    private Timestamp notificationDeliveredAt(UUID offlineTxnId) {
        return jdbc.queryForObject(
                "SELECT delivered_at FROM notification_log WHERE offline_transaction_id = ?",
                Timestamp.class, offlineTxnId);
    }
}
