package com.dompetgaruda.api.transaction;

import com.dompetgaruda.api.ApiIntegrationTestBase;
import com.dompetgaruda.api.DeviceIdTestSupport;
import com.dompetgaruda.api.Ed25519TestSupport;
import com.dompetgaruda.api.device.dto.CreateUserRequest;
import com.dompetgaruda.api.device.dto.CreateUserResponse;
import com.dompetgaruda.api.device.dto.RegisterDeviceRequest;
import com.dompetgaruda.api.device.dto.RegisterDeviceResponse;
import com.dompetgaruda.api.sync.SyncSettlementService;
import com.dompetgaruda.api.sync.dto.SyncBatchRequest;
import com.dompetgaruda.api.sync.dto.SyncBatchResponse;
import com.dompetgaruda.api.sync.dto.SyncOfflineTxnRequest;
import com.dompetgaruda.api.transaction.dto.TransactionHistoryPageDto;
import com.dompetgaruda.api.transfer.dto.TransferRequest;
import com.dompetgaruda.api.transfer.dto.TransferResponse;
import com.dompetgaruda.api.wallet.dto.PouchLoadRequest;
import com.dompetgaruda.api.wallet.dto.PouchLoadResponse;
import com.dompetgaruda.api.wallet.dto.TopUpRequest;
import com.dompetgaruda.api.wallet.dto.TopUpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Phase 3 Feature B — {@code GET /device/transactions} (CLAUDE.md §18).
 *
 * <p>Cases covered:
 * <ol>
 *   <li>SUCCESS status for a settled TOPUP and a settled ONLINE_TRANSFER.</li>
 *   <li>Pagination — page/size honoured, totalElements/totalPages correct.</li>
 *   <li>Type filter — only rows of the requested type are returned.</li>
 *   <li>Date range filter — rows outside [from,to) excluded.</li>
 *   <li>PENDING status for an uploaded-but-unsettled offline transfer.</li>
 *   <li>FAILED status for a settlement-rejected sub-transaction (BAD_SIGNATURE).</li>
 *   <li>Zero-write invariant — the endpoint makes no ledger writes.</li>
 *   <li>Missing Device-Id header returns 401.</li>
 * </ol>
 */
class TransactionHistoryTest extends ApiIntegrationTestBase {

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired SyncSettlementService settlementService;
    @Autowired ObjectMapper objectMapper;

    // -------------------------------------------------------------------------
    // SUCCESS
    // -------------------------------------------------------------------------

    @Test
    void history_afterTopUpAndTransfer_returnsSuccessRows() {
        UUID senderId = createUser("+62888000001");
        UUID receiverId = createUser("+62888000002");
        RegisterDeviceResponse sender = registerDevice(senderId, "pk-hist-001");
        registerDevice(receiverId, "pk-hist-002");
        topUp(senderId, 200_000L);

        transfer(sender, receiverId, 50_000L);

        TransactionHistoryPageDto page = getHistory(sender.deviceId(), null, null, null, 0, 20);

        assertThat(page.content()).anySatisfy(item -> {
            assertThat(item.type()).isEqualTo("TOPUP");
            assertThat(item.status()).isEqualTo(com.dompetgaruda.api.transaction.dto.TransactionStatus.SUCCESS);
            assertThat(item.direction()).isEqualTo("CREDIT");
            assertThat(item.amount()).isEqualTo(200_000L);
        });
        assertThat(page.content()).anySatisfy(item -> {
            assertThat(item.type()).isEqualTo("ONLINE_TRANSFER");
            assertThat(item.status()).isEqualTo(com.dompetgaruda.api.transaction.dto.TransactionStatus.SUCCESS);
            assertThat(item.direction()).isEqualTo("DEBIT");
            assertThat(item.amount()).isEqualTo(50_000L);
            assertThat(item.transactionId()).isNotNull();
        });
    }

    // -------------------------------------------------------------------------
    // Pagination
    // -------------------------------------------------------------------------

    @Test
    void history_pagination_respectsPageAndSize() {
        UUID userId = createUser("+62888000003");
        RegisterDeviceResponse reg = registerDevice(userId, "pk-hist-003");
        for (int i = 0; i < 5; i++) {
            topUp(userId, 1_000L);
        }

        TransactionHistoryPageDto pageZero = getHistory(reg.deviceId(), null, null, null, 0, 2);
        TransactionHistoryPageDto pageOne = getHistory(reg.deviceId(), null, null, null, 1, 2);

        assertThat(pageZero.content()).hasSize(2);
        assertThat(pageOne.content()).hasSize(2);
        assertThat(pageZero.totalElements()).isEqualTo(5);
        assertThat(pageZero.totalPages()).isEqualTo(3);
        assertThat(pageZero.page()).isEqualTo(0);
        assertThat(pageOne.page()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Type filter
    // -------------------------------------------------------------------------

    @Test
    void history_typeFilter_returnsOnlyMatchingType() {
        UUID senderId = createUser("+62888000004");
        UUID receiverId = createUser("+62888000005");
        RegisterDeviceResponse sender = registerDevice(senderId, "pk-hist-004");
        registerDevice(receiverId, "pk-hist-005");
        topUp(senderId, 100_000L);
        transfer(sender, receiverId, 20_000L);

        TransactionHistoryPageDto filtered = getHistory(sender.deviceId(), "ONLINE_TRANSFER", null, null, 0, 20);

        assertThat(filtered.content()).isNotEmpty();
        assertThat(filtered.content()).allSatisfy(item -> assertThat(item.type()).isEqualTo("ONLINE_TRANSFER"));
    }

    // -------------------------------------------------------------------------
    // Date range filter
    // -------------------------------------------------------------------------

    @Test
    void history_dateRangeFilter_excludesRowsOutsideRange() {
        UUID userId = createUser("+62888000006");
        RegisterDeviceResponse reg = registerDevice(userId, "pk-hist-006");
        topUp(userId, 10_000L);

        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
        TransactionHistoryPageDto tooLate = getHistory(reg.deviceId(), null, future, null, 0, 20);
        assertThat(tooLate.content()).isEmpty();

        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        TransactionHistoryPageDto included = getHistory(reg.deviceId(), null, past, null, 0, 20);
        assertThat(included.content()).isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // PENDING — uploaded, not yet settled
    // -------------------------------------------------------------------------

    @Test
    void history_uploadedButUnsettledOfflineTransfer_returnsPending() throws Exception {
        UUID senderId = createUser("+62888000007");
        UUID receiverId = createUser("+62888000008");
        RegisterDeviceResponse sender = registerDevice(senderId, "pk-hist-007");
        RegisterDeviceResponse receiver = registerDevice(receiverId, "pk-hist-008");
        topUp(senderId, 200_000L);
        PouchLoadResponse cert = loadPouch(sender.deviceId(), 100_000L);

        UUID offlineTxnId = UUID.randomUUID();
        SyncOfflineTxnRequest txn = new SyncOfflineTxnRequest(
                offlineTxnId, receiver.deviceId(), 40_000L, 1L, null,
                "irrelevant-not-yet-verified", "irrelevant-not-yet-verified", "BLE");
        String batchJson = objectMapper.writeValueAsString(
                new SyncBatchRequest(cert.certificateId(), List.of(txn)));

        ResponseEntity<SyncBatchResponse> syncResp = rest.exchange(
                "/device/sync", HttpMethod.POST,
                new HttpEntity<>(batchJson, deviceIdHeaders(sender.deviceId())),
                SyncBatchResponse.class);
        assertThat(syncResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // Deliberately do NOT settle — the batch stays PENDING in sync_inbox.
        TransactionHistoryPageDto page = getHistory(sender.deviceId(), "OFFLINE_TRANSFER", null, null, 0, 20);

        assertThat(page.content()).hasSize(1);
        var item = page.content().get(0);
        assertThat(item.status()).isEqualTo(com.dompetgaruda.api.transaction.dto.TransactionStatus.PENDING);
        assertThat(item.transactionId()).isNull();
        assertThat(item.referenceId()).isEqualTo(offlineTxnId.toString());
        assertThat(item.amount()).isEqualTo(40_000L);
        assertThat(item.direction()).isEqualTo("DEBIT");
    }

    // -------------------------------------------------------------------------
    // FAILED — flagged, never posted
    // -------------------------------------------------------------------------

    @Test
    void history_settlementRejectedSubTransaction_returnsFailed() throws Exception {
        UUID senderId = createUser("+62888000009");
        UUID receiverId = createUser("+62888000010");
        RegisterDeviceResponse sender = registerDevice(senderId, "pk-hist-009");
        RegisterDeviceResponse receiver = registerDevice(receiverId, "pk-hist-010");
        topUp(senderId, 200_000L);
        PouchLoadResponse cert = loadPouch(sender.deviceId(), 100_000L);

        UUID offlineTxnId = UUID.randomUUID();
        // Garbage (but Base64-decodable) signatures — Ed25519Verifier returns false rather
        // than throwing, so this settles as a BAD_SIGNATURE flag with offline_txn_id = null.
        SyncOfflineTxnRequest txn = new SyncOfflineTxnRequest(
                offlineTxnId, receiver.deviceId(), 40_000L, 1L, null,
                "aW52YWxpZC1zaWduYXR1cmU=", "aW52YWxpZC1zaWduYXR1cmU=", "BLE");
        String batchJson = objectMapper.writeValueAsString(
                new SyncBatchRequest(cert.certificateId(), List.of(txn)));

        ResponseEntity<SyncBatchResponse> syncResp = rest.exchange(
                "/device/sync", HttpMethod.POST,
                new HttpEntity<>(batchJson, deviceIdHeaders(sender.deviceId())),
                SyncBatchResponse.class);
        assertThat(syncResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        settlementService.settle(syncResp.getBody().batchId());

        TransactionHistoryPageDto page = getHistory(sender.deviceId(), "OFFLINE_TRANSFER", null, null, 0, 20);

        assertThat(page.content()).hasSize(1);
        var item = page.content().get(0);
        assertThat(item.status()).isEqualTo(com.dompetgaruda.api.transaction.dto.TransactionStatus.FAILED);
        assertThat(item.transactionId()).isNull();
        assertThat(item.notes()).contains("BAD_SIGNATURE");
    }

    // -------------------------------------------------------------------------
    // Zero-write invariant
    // -------------------------------------------------------------------------

    @Test
    void history_makesZeroWritesToLedger() {
        UUID userId = createUser("+62888000011");
        RegisterDeviceResponse reg = registerDevice(userId, "pk-hist-011");
        topUp(userId, 50_000L);

        long entriesBefore = countRows("ledger_entries");
        long txnsBefore = countRows("ledger_transactions");

        getHistory(reg.deviceId(), null, null, null, 0, 20);

        assertThat(countRows("ledger_entries")).isEqualTo(entriesBefore);
        assertThat(countRows("ledger_transactions")).isEqualTo(txnsBefore);
    }

    // -------------------------------------------------------------------------
    // Auth guard
    // -------------------------------------------------------------------------

    @Test
    void history_missingDeviceIdHeader_returns401() {
        ResponseEntity<String> resp = rest.getForEntity("/device/transactions", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -------------------------------------------------------------------------
    // Setup / HTTP helpers
    // -------------------------------------------------------------------------

    private UUID createUser(String phone) {
        return adminPost("/admin/users", new CreateUserRequest("Test User", phone), CreateUserResponse.class).userId();
    }

    private RegisterDeviceResponse registerDevice(UUID userId, String publicKey) {
        return adminPost("/admin/devices",
                new RegisterDeviceRequest(userId, DeviceIdTestSupport.randomDeviceId(),
                        Ed25519TestSupport.derivePublicKeyBase64(publicKey), "Test Device"),
                RegisterDeviceResponse.class);
    }

    private void topUp(UUID userId, long amount) {
        adminPost("/admin/users/" + userId + "/topup", new TopUpRequest(amount, "test-topup"), TopUpResponse.class);
    }

    private PouchLoadResponse loadPouch(String deviceId, long amount) {
        ResponseEntity<PouchLoadResponse> resp = rest.exchange(
                "/device/pouch/load", HttpMethod.POST,
                new HttpEntity<>(new PouchLoadRequest(amount), deviceIdHeaders(deviceId)),
                PouchLoadResponse.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        return resp.getBody();
    }

    private void transfer(RegisterDeviceResponse sender, UUID receiverUserId, long amount) {
        String receiverDeviceId = jdbc.queryForObject(
                "SELECT device_id FROM devices WHERE user_id = ?", String.class, receiverUserId);
        HttpHeaders headers = deviceIdHeaders(sender.deviceId());
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<TransferResponse> resp = rest.exchange(
                "/device/transfer", HttpMethod.POST,
                new HttpEntity<>(new TransferRequest(receiverDeviceId, amount), headers),
                TransferResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private TransactionHistoryPageDto getHistory(
            String deviceId, String type, Instant from, Instant to, int page, int size) {
        StringBuilder url = new StringBuilder("/device/transactions?page=" + page + "&size=" + size);
        if (type != null) url.append("&type=").append(type);
        if (from != null) url.append("&from=").append(from);
        if (to != null) url.append("&to=").append(to);

        ResponseEntity<TransactionHistoryPageDto> resp = rest.exchange(
                url.toString(), HttpMethod.GET,
                new HttpEntity<>(deviceIdHeaders(deviceId)),
                TransactionHistoryPageDto.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private <T> T adminPost(String path, Object body, Class<T> responseType) {
        ResponseEntity<T> resp = rest.postForEntity(path, new HttpEntity<>(body, adminHeaders()), responseType);
        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("Expected 2xx from %s but got %s: %s", path, resp.getStatusCode(), resp.getBody())
                .isTrue();
        return resp.getBody();
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(testAdminJwt());
        return h;
    }

    private HttpHeaders deviceIdHeaders(String deviceId) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (deviceId != null) {
            h.set("Device-Id", deviceId);
        }
        return h;
    }

    private long countRows(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }
}
