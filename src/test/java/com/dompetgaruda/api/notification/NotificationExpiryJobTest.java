package com.dompetgaruda.api.notification;

import com.dompetgaruda.api.DeviceIdTestSupport;
import com.dompetgaruda.api.WorkerIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 Feature A (CLAUDE.md §17 point 5) — AC 7: a PENDING notification past its expiry
 * window is marked EXPIRED by the hourly sweep, and this never touches the ledger.
 *
 * <p>Inserts rows directly (no real settlement needed — {@code NotificationReconciliationSettlementTest}
 * already covers the settlement→notification wiring); this test is purely about
 * {@link NotificationExpiryJob#doExpire()}'s own SQL.
 */
class NotificationExpiryJobTest extends WorkerIntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationExpiryJob expiryJob;

    private UUID userId;
    private String deviceId;
    private UUID pouchAccountId;
    private UUID certId;
    private UUID staleOfflineTxnId;
    private UUID freshOfflineTxnId;

    @BeforeEach
    void setup() {
        userId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (user_id, full_name, phone) VALUES (?, ?, ?)",
                userId, "Notif Expiry Test User", "+62802" + System.nanoTime() % 100_000_000L);

        deviceId = DeviceIdTestSupport.randomDeviceId();
        String tokHash = UUID.randomUUID().toString().replace("-", "")
                       + UUID.randomUUID().toString().replace("-", "");
        String pubKey = Base64.getEncoder().encodeToString(new byte[32]) + "-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO devices (device_id, user_id, public_key, device_label, device_token_hash) " +
                "VALUES (?, ?, ?, 'Notif Expiry Test Device', ?)",
                deviceId, userId, pubKey, tokHash);

        pouchAccountId = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (account_id, user_id, device_id, type) VALUES (?, ?, ?, 'POUCH')",
                pouchAccountId, userId, deviceId);

        certId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO offline_certificates " +
                "(certificate_id, device_id, pouch_account_id, issued_amount, server_signature, status, expires_at) " +
                "VALUES (?, ?, ?, 100000, 'test-sig', 'SETTLED', now() + interval '1 day')",
                certId, deviceId, pouchAccountId);

        staleOfflineTxnId = insertOfflineTransaction(1L);
        freshOfflineTxnId = insertOfflineTransaction(2L);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM notification_log WHERE device_id = ?", deviceId);
        jdbc.update("DELETE FROM offline_transactions WHERE receiver_device_id = ?", deviceId);
        jdbc.update("DELETE FROM offline_certificates WHERE device_id = ?", deviceId);
        jdbc.update("DELETE FROM accounts WHERE account_id = ?", pouchAccountId);
        jdbc.update("DELETE FROM devices WHERE device_id = ?", deviceId);
        jdbc.update("DELETE FROM users WHERE user_id = ?", userId);
    }

    @Test
    void staleRow_pastExpiry_marksExpired_freshRow_untouched() {
        jdbc.update(
                "INSERT INTO notification_log (offline_transaction_id, device_id, status, expires_at) " +
                "VALUES (?, ?, 'PENDING', now() - interval '1 minute')",
                staleOfflineTxnId, deviceId);
        jdbc.update(
                "INSERT INTO notification_log (offline_transaction_id, device_id, status, expires_at) " +
                "VALUES (?, ?, 'PENDING', now() + interval '3 days')",
                freshOfflineTxnId, deviceId);

        long ledgerRowsBefore = countLedgerTransactions();

        expiryJob.doExpire();

        assertThat(status(staleOfflineTxnId)).isEqualTo("EXPIRED");
        assertThat(status(freshOfflineTxnId)).isEqualTo("PENDING");
        // §7 invariant 1 / §17 point 5 — this job never writes to the ledger.
        assertThat(countLedgerTransactions()).isEqualTo(ledgerRowsBefore);
    }

    @Test
    void deliveredRow_pastExpiry_isNeverTouched() {
        jdbc.update(
                "INSERT INTO notification_log (offline_transaction_id, device_id, status, delivered_at, expires_at) " +
                "VALUES (?, ?, 'DELIVERED', now(), now() - interval '1 minute')",
                staleOfflineTxnId, deviceId);

        expiryJob.doExpire();

        assertThat(status(staleOfflineTxnId)).isEqualTo("DELIVERED");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UUID insertOfflineTransaction(long counter) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO offline_transactions " +
                "(offline_txn_id, sender_device_id, receiver_device_id, certificate_id, amount, counter, " +
                " sender_signature, receiver_signature, settlement_status, settled_at) " +
                "VALUES (?, ?, ?, ?, 10000, ?, 'sig', 'sig', 'SETTLED', now())",
                id, deviceId, deviceId, certId, counter);
        return id;
    }

    private String status(UUID offlineTxnId) {
        return jdbc.queryForObject(
                "SELECT status FROM notification_log WHERE offline_transaction_id = ?", String.class, offlineTxnId);
    }

    private long countLedgerTransactions() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transactions", Long.class);
        return count == null ? 0L : count;
    }
}
