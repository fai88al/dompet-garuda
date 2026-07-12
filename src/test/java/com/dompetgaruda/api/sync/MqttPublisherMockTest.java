package com.dompetgaruda.api.sync;

import com.dompetgaruda.api.WorkerIntegrationTestBase;
import com.dompetgaruda.api.ledger.LedgerEntry;
import com.dompetgaruda.api.ledger.LedgerPostingService;
import com.dompetgaruda.api.ledger.PostingRequest;
import com.dompetgaruda.api.mqtt.MqttPublisherService;
import com.dompetgaruda.api.sync.dto.SyncBatchRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Verifies that {@link SyncSettlementService} calls
 * {@link MqttPublisherService#publishSyncResult} with the correct arguments after a successful
 * settlement, using a Mockito mock in place of the real publisher.
 */
class MqttPublisherMockTest extends WorkerIntegrationTestBase {

    @Autowired SyncInboxPoller     poller;
    @Autowired JdbcTemplate        jdbc;
    @Autowired LedgerPostingService ledger;
    @Autowired ObjectMapper         objectMapper;

    @MockitoBean MqttPublisherService mqttPublisher;

    private UUID userId, deviceId, onlineAccountId, pouchAccountId;

    private static final long   ISSUED_AMOUNT = 50_000L;
    private static final String DUMMY_PUB_KEY =
            Base64.getEncoder().encodeToString(new byte[32]);

    @BeforeEach
    void setup() {
        userId          = insertUser();
        deviceId        = insertDevice(userId);
        onlineAccountId = insertAccount(userId, null,     "ONLINE");
        pouchAccountId  = insertAccount(userId, deviceId, "POUCH");
        setupLedgerBalance(ISSUED_AMOUNT);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM sync_inbox WHERE device_id = ?", deviceId);
        jdbc.update(
                "DELETE FROM ledger_entries WHERE transaction_id IN (" +
                "  SELECT DISTINCT transaction_id FROM ledger_entries " +
                "  WHERE account_id IN (?, ?)" +
                ")", onlineAccountId, pouchAccountId);
        jdbc.update(
                "DELETE FROM ledger_transactions WHERE transaction_id NOT IN " +
                "(SELECT transaction_id FROM ledger_entries)");
        jdbc.update("DELETE FROM offline_certificates WHERE device_id = ?", deviceId);
        jdbc.update("DELETE FROM accounts WHERE account_id IN (?, ?)",
                onlineAccountId, pouchAccountId);
        jdbc.update("DELETE FROM devices WHERE device_id = ?", deviceId);
        jdbc.update("DELETE FROM users WHERE user_id = ?", userId);
    }

    // -------------------------------------------------------------------------
    // Test: successful settlement → publishSyncResult("SETTLED") called
    // -------------------------------------------------------------------------

    @Test
    void successfulSettlement_publishesSyncResultSettled() throws Exception {
        UUID certId  = insertCert(deviceId, pouchAccountId, ISSUED_AMOUNT,
                Instant.now().plusSeconds(86400));
        UUID batchId = UUID.randomUUID();
        insertBatch(batchId, deviceId, certId, List.of());

        poller.processOneRow();

        assertThat(batchStatus(batchId)).isEqualTo("DONE");
        verify(mqttPublisher).publishSyncResult(
                deviceId.toString(), batchId.toString(), "SETTLED", null);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void setupLedgerBalance(long amount) {
        ledger.post(new PostingRequest("TOPUP", "TEST", "mqtt-mock-setup", "Test top-up",
                List.of(
                        new LedgerEntry(LedgerPostingService.SYSTEM_ACCOUNT_ID, "DEBIT",  amount),
                        new LedgerEntry(onlineAccountId,                         "CREDIT", amount)
                )));
        ledger.post(new PostingRequest("POUCH_LOAD", "TEST", "mqtt-mock-setup", "Test pouch load",
                List.of(
                        new LedgerEntry(onlineAccountId, "DEBIT",  amount),
                        new LedgerEntry(pouchAccountId,  "CREDIT", amount)
                )));
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (user_id, full_name, phone) VALUES (?, ?, ?)",
                id, "MQTT Mock User", "+6282" + System.nanoTime() % 100_000_000L);
        return id;
    }

    private UUID insertDevice(UUID uid) {
        UUID id = UUID.randomUUID();
        String tokHash = UUID.randomUUID().toString().replace("-", "")
                       + UUID.randomUUID().toString().replace("-", "");
        jdbc.update(
                "INSERT INTO devices (device_id, user_id, public_key, device_label, device_token_hash) " +
                "VALUES (?, ?, ?, 'MQTT Mock Device', ?)",
                id, uid, DUMMY_PUB_KEY, tokHash);
        return id;
    }

    private UUID insertAccount(UUID uid, UUID devId, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (account_id, user_id, device_id, type) VALUES (?, ?, ?, ?)",
                id, uid, devId, type);
        return id;
    }

    private UUID insertCert(UUID devId, UUID pouchAccId, long amount, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO offline_certificates " +
                "(certificate_id, device_id, pouch_account_id, issued_amount, server_signature, status, expires_at) " +
                "VALUES (?, ?, ?, ?, 'test-sig', 'ACTIVE', ?)",
                id, devId, pouchAccId, amount, Timestamp.from(expiresAt));
        return id;
    }

    private void insertBatch(UUID batchId, UUID devId, UUID certId,
                              List<com.dompetgaruda.api.sync.dto.SyncOfflineTxnRequest> txns)
            throws Exception {
        String json = objectMapper.writeValueAsString(new SyncBatchRequest(certId, txns));
        jdbc.update(
                "INSERT INTO sync_inbox (batch_id, device_id, raw_payload, status, synced_after_expiry) " +
                "VALUES (?, ?, ?::jsonb, 'PENDING', false)",
                batchId, devId, json);
    }

    private String batchStatus(UUID batchId) {
        return jdbc.queryForObject(
                "SELECT status FROM sync_inbox WHERE batch_id = ?", String.class, batchId);
    }
}
