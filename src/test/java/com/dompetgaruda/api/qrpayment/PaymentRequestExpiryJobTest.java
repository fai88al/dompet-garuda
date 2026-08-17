package com.dompetgaruda.api.qrpayment;

import com.dompetgaruda.api.WorkerIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for FR22 — {@link PaymentRequestExpiryJob}.
 * Runs with the {@code worker} profile and a real Testcontainers Postgres instance.
 */
class PaymentRequestExpiryJobTest extends WorkerIntegrationTestBase {

    @Autowired PaymentRequestExpiryJob job;
    @Autowired JdbcTemplate jdbc;

    @Test
    void sweep_expiresPastDuePendingRequests_zeroLedgerWrites() {
        UUID receiverId = insertUser("Receiver");
        UUID requestId = insertPendingRequest(receiverId, 10_000L, Instant.now().minus(1, ChronoUnit.MINUTES));

        long ledgerEntriesBefore = countRows("ledger_entries");

        int updated = job.sweep();

        assertThat(updated).isGreaterThanOrEqualTo(1);
        String status = jdbc.queryForObject(
                "SELECT status FROM payment_requests WHERE request_id = ?", String.class, requestId);
        assertThat(status).isEqualTo("EXPIRED");
        assertThat(countRows("ledger_entries")).isEqualTo(ledgerEntriesBefore);
    }

    @Test
    void sweep_leavesNonExpiredPendingRequestsUntouched() {
        UUID receiverId = insertUser("Receiver 2");
        UUID requestId = insertPendingRequest(receiverId, 10_000L, Instant.now().plus(10, ChronoUnit.MINUTES));

        job.sweep();

        String status = jdbc.queryForObject(
                "SELECT status FROM payment_requests WHERE request_id = ?", String.class, requestId);
        assertThat(status).isEqualTo("PENDING");
    }

    @Test
    void expirePaymentRequests_afterExecution_shedlockRowExists() {
        job.expirePaymentRequests();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shedlock WHERE name = 'payment-request-expiry'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UUID insertUser(String name) {
        UUID userId = UUID.randomUUID();
        String phone = "+6280" + System.nanoTime() % 1_000_000_000L;
        jdbc.update("INSERT INTO users (user_id, full_name, phone) VALUES (?, ?, ?)", userId, name, phone);
        return userId;
    }

    private UUID insertPendingRequest(UUID receiverUserId, long amount, Instant expiresAt) {
        UUID requestId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO payment_requests (request_id, receiver_user_id, amount, nonce, expires_at) " +
                "VALUES (?, ?, ?, ?, ?)",
                requestId, receiverUserId, amount, UUID.randomUUID().toString(), Timestamp.from(expiresAt));
        return requestId;
    }

    private long countRows(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }
}
