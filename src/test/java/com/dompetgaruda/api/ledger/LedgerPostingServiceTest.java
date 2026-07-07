package com.dompetgaruda.api.ledger;

import com.dompetgaruda.api.ApiIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for the double-entry ledger engine.
 * All tests run against a real Postgres container (CLAUDE.md §10 — no mocking for money logic).
 *
 * <p>Cases covered:
 * <ol>
 *   <li>Balanced posting succeeds and returns a positive transaction id.</li>
 *   <li>Unbalanced posting throws {@link UnbalancedPostingException} and rolls back —
 *       no rows are written to either {@code ledger_transactions} or {@code ledger_entries}.</li>
 *   <li>Balance derivation is correct across multiple independent postings.</li>
 *   <li>TOPUP-shaped posting (DEBIT system, CREDIT user.online) produces the correct
 *       online balance as derived from the ledger.</li>
 * </ol>
 */
class LedgerPostingServiceTest extends ApiIntegrationTestBase {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("admin.api-token", () -> "test-admin-token");
    }

    @Autowired
    LedgerPostingService ledger;

    @Autowired
    JdbcTemplate jdbc;

    // -------------------------------------------------------------------------
    // Test 1: balanced posting succeeds
    // -------------------------------------------------------------------------

    @Test
    void balancedPosting_succeeds_andTransactionIdIsPositive() {
        UUID userId = createUserWithOnlineAccount();
        UUID onlineId = ledger.resolveOnlineAccount(userId);

        long txnId = ledger.post(new PostingRequest(
                "TOPUP", null, null, "test top-up",
                List.of(
                        new LedgerEntry(ledger.resolveSystemAccount(), "DEBIT",  10_000L),
                        new LedgerEntry(onlineId,                     "CREDIT", 10_000L)
                )
        ));

        assertThat(txnId).isPositive();

        Integer entryCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE transaction_id = ?",
                Integer.class, txnId);
        assertThat(entryCount).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // Test 2: unbalanced posting throws and rolls back — no rows written
    // -------------------------------------------------------------------------

    @Test
    void unbalancedPosting_throwsUnbalancedPostingException_andRollsBack() {
        UUID userId = createUserWithOnlineAccount();
        UUID onlineId = ledger.resolveOnlineAccount(userId);

        long txnCountBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions", Long.class);

        assertThatThrownBy(() -> ledger.post(new PostingRequest(
                "TOPUP", null, null, "bad posting",
                List.of(
                        new LedgerEntry(ledger.resolveSystemAccount(), "DEBIT",  5_000L),
                        new LedgerEntry(onlineId,                     "CREDIT", 3_000L) // ≠ 5000
                )
        ))).isInstanceOf(UnbalancedPostingException.class)
                .hasMessageContaining("totalCredit=3000")
                .hasMessageContaining("totalDebit=5000");

        long txnCountAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions", Long.class);
        assertThat(txnCountAfter).isEqualTo(txnCountBefore); // nothing committed
    }

    // -------------------------------------------------------------------------
    // Test 3: balance derivation is correct across multiple postings
    // -------------------------------------------------------------------------

    @Test
    void balanceDerivedCorrectly_acrossMultiplePostings() {
        UUID userId = createUserWithOnlineAccount();
        UUID onlineId = ledger.resolveOnlineAccount(userId);

        ledger.post(new PostingRequest("TOPUP", null, null, null, List.of(
                new LedgerEntry(ledger.resolveSystemAccount(), "DEBIT",  20_000L),
                new LedgerEntry(onlineId,                     "CREDIT", 20_000L)
        )));
        ledger.post(new PostingRequest("TOPUP", null, null, null, List.of(
                new LedgerEntry(ledger.resolveSystemAccount(), "DEBIT",  30_000L),
                new LedgerEntry(onlineId,                     "CREDIT", 30_000L)
        )));

        assertThat(ledger.getBalance(onlineId)).isEqualTo(50_000L);
    }

    // -------------------------------------------------------------------------
    // Test 4: TOPUP-shaped posting produces correct online balance
    // -------------------------------------------------------------------------

    @Test
    void topupPosting_debitSystemCreditOnline_producesCorrectOnlineBalance() {
        UUID userId = createUserWithOnlineAccount();
        UUID onlineId = ledger.resolveOnlineAccount(userId);

        assertThat(ledger.getBalance(onlineId)).isZero(); // fresh account starts at 0

        ledger.post(new PostingRequest(
                "TOPUP", null, null, "initial load",
                List.of(
                        new LedgerEntry(ledger.resolveSystemAccount(), "DEBIT",  100_000L),
                        new LedgerEntry(onlineId,                     "CREDIT", 100_000L)
                )
        ));

        // Online account balance == top-up amount
        assertThat(ledger.getBalance(onlineId)).isEqualTo(100_000L);
    }

    // -------------------------------------------------------------------------
    // Helper: insert a user + ONLINE account directly via SQL
    // -------------------------------------------------------------------------

    /**
     * Creates a user and an ONLINE ledger account via direct SQL (no HTTP layer).
     * Phone is derived from the UUID to guarantee uniqueness across tests.
     *
     * @return the new user's UUID
     */
    private UUID createUserWithOnlineAccount() {
        UUID userId = UUID.randomUUID();
        // Derive a unique 10-digit phone suffix from the UUID
        String digits = userId.toString().replaceAll("[^0-9]", "");
        String phone = "+628" + digits.substring(0, Math.min(10, digits.length()));

        jdbc.update("INSERT INTO users (user_id, full_name, phone) VALUES (?, ?, ?)",
                userId, "Test User", phone);
        UUID accountId = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (account_id, user_id, type) VALUES (?, ?, 'ONLINE')",
                accountId, userId);
        return userId;
    }
}
