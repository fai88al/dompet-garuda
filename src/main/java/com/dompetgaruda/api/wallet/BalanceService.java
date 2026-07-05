package com.dompetgaruda.api.wallet;

import com.dompetgaruda.api.common.entity.Device;
import com.dompetgaruda.api.ledger.LedgerPostingService;
import com.dompetgaruda.api.wallet.dto.BalanceResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Read-only balance service for FR14 — Cek Saldo.
 *
 * <p>Returns two figures (CLAUDE.md §3 "Two balance views"):
 * <ol>
 *   <li><b>onlineBalance</b> — SUM(CREDIT) − SUM(DEBIT) over the user's ONLINE
 *       ledger account. This is the authoritative server-side balance.</li>
 *   <li><b>pouchCommitted</b> — the {@code issued_amount} on the device's ACTIVE
 *       offline certificate, or 0 if no active certificate exists.</li>
 * </ol>
 *
 * <p>This service performs ZERO ledger writes (CLAUDE.md §7 rule 1 / FR14 invariant).
 * Any modification to this class that introduces a write is a bug.
 */
@Service
public class BalanceService {

    private final LedgerPostingService ledger;
    private final JdbcTemplate jdbc;

    public BalanceService(LedgerPostingService ledger, JdbcTemplate jdbc) {
        this.ledger = ledger;
        this.jdbc   = jdbc;
    }

    /**
     * Derives the online balance and pouch-committed figure for the calling device.
     *
     * @param device the authenticated device (resolved from Bearer token)
     * @return {@link BalanceResponse} with both figures; never null
     */
    public BalanceResponse getBalance(Device device) {
        UUID onlineAccountId = ledger.resolveOnlineAccount(device.getUserId());
        long onlineBalance   = ledger.getBalance(onlineAccountId);

        // COALESCE returns 0 when no ACTIVE certificate exists for this device.
        // The unique index uq_active_cert_per_device guarantees at most one ACTIVE row.
        Long pouchCommitted = jdbc.queryForObject(
                "SELECT COALESCE(" +
                "  (SELECT issued_amount FROM offline_certificates " +
                "   WHERE device_id = ? AND status = 'ACTIVE')," +
                "  0" +
                ")",
                Long.class,
                device.getDeviceId());

        return new BalanceResponse(onlineBalance, pouchCommitted == null ? 0L : pouchCommitted);
    }
}
