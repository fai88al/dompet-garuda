package com.dompetgaruda.api.transaction;

import com.dompetgaruda.api.ledger.LedgerPostingService;
import com.dompetgaruda.api.sync.dto.SyncBatchRequest;
import com.dompetgaruda.api.sync.dto.SyncOfflineTxnRequest;
import com.dompetgaruda.api.transaction.dto.TransactionHistoryItemDto;
import com.dompetgaruda.api.transaction.dto.TransactionHistoryPageDto;
import com.dompetgaruda.api.transaction.dto.TransactionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Read-only transaction history queries (CLAUDE.md §18, PRD v1.1 §3.2-§3.3). Backs both
 * {@code GET /device/transactions} and {@code GET /admin/users/{userId}/transactions} — the
 * only difference between the two is which device ids scope the pouch-side rows.
 *
 * <p>Pure reads over {@code ledger_transactions}/{@code ledger_entries},
 * {@code sync_inbox}, and {@code flagged_transactions} — no new balance source (§7
 * invariant 1). {@code GET /device/balance} never uses this service and this service
 * never writes anything.
 *
 * <h2>Status derivation (CLAUDE.md §18 §3.3) — never stored redundantly</h2>
 * <ul>
 *   <li>SUCCESS — a settled {@code ledger_transactions} row exists.</li>
 *   <li>PENDING — an offline transaction is still sitting in a {@code sync_inbox} batch
 *       that hasn't been worker-settled yet. Only the sending device can see these (only
 *       the sender uploads a batch; the receiver has no row until settlement).</li>
 *   <li>FAILED — a {@code flagged_transactions} row with no {@code offline_txn_id}: the
 *       sub-transaction was rejected during settlement and no ledger posting, and no
 *       {@code offline_transactions} row, was ever created for it (CLAUDE.md §7 rule 5).
 *       <b>Known, accepted limitation:</b> the original amount/receiver for a rejected
 *       sub-transaction is not retained anywhere in the schema once rejected (by design —
 *       nothing is written for a transaction that never happened), so these rows report
 *       {@code amount = 0} and {@code counterparty = null}. The rejection reason/detail is
 *       preserved in {@code notes}. This is not fixed as a side effect of this PR; it would
 *       require a schema change to retain rejected-transaction context, which is a separate,
 *       scoped decision.</li>
 *   <li>REVERSED — reserved in the enum, never produced here (CLAUDE.md §18 Open Decision 2:
 *       a real reversal mechanism is a separate, later milestone).</li>
 * </ul>
 *
 * <p>Small-scale prototype approach (CLAUDE.md §1 "prefer simple, correct, auditable"):
 * the three sources are queried independently, merged, sorted, and paginated in memory
 * rather than with one complex UNION query — simple and auditable at this data volume.
 */
@Service
public class TransactionHistoryService {

    private static final Logger log = LoggerFactory.getLogger(TransactionHistoryService.class);

    private final JdbcTemplate jdbc;
    private final LedgerPostingService ledger;
    private final ObjectMapper objectMapper;

    public TransactionHistoryService(JdbcTemplate jdbc, LedgerPostingService ledger, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.objectMapper = objectMapper;
    }

    /**
     * @param userId     owning user — resolves the ONLINE account
     * @param deviceIds  device(s) whose POUCH activity and pending/failed offline sends
     *                   are in scope — exactly one for {@code GET /device/transactions},
     *                   every device the user owns for the admin endpoint
     * @throws EmptyResultDataAccessException if {@code userId} has no ONLINE account
     *         (i.e. does not exist) — callers translate this to 404
     */
    public TransactionHistoryPageDto getHistory(
            UUID userId, List<String> deviceIds, String typeFilter,
            Instant from, Instant to, int page, int size) {

        UUID onlineAccountId = ledger.resolveOnlineAccount(userId);
        List<UUID> pouchAccountIds = new ArrayList<>();
        for (String deviceId : deviceIds) {
            try {
                pouchAccountIds.add(ledger.resolvePouchAccount(deviceId));
            } catch (EmptyResultDataAccessException e) {
                log.warn("Device {} has no POUCH account; skipping in transaction history", deviceId);
            }
        }

        List<TransactionHistoryItemDto> items = new ArrayList<>();
        items.addAll(fetchSuccess(onlineAccountId, pouchAccountIds, typeFilter, from, to));
        if (typeFilter == null || "OFFLINE_TRANSFER".equals(typeFilter)) {
            items.addAll(fetchPending(deviceIds, from, to));
            items.addAll(fetchFailed(deviceIds, from, to));
        }

        items.sort(Comparator.comparing(TransactionHistoryItemDto::createdAt).reversed());

        long totalElements = items.size();
        int totalPages = size == 0 ? 0 : (int) Math.ceil(totalElements / (double) size);
        int fromIdx = Math.min(page * size, items.size());
        int toIdx = Math.min(fromIdx + size, items.size());
        List<TransactionHistoryItemDto> pageContent = items.subList(fromIdx, toIdx);

        return new TransactionHistoryPageDto(pageContent, page, size, totalElements, totalPages);
    }

    // -------------------------------------------------------------------------
    // SUCCESS — settled ledger_transactions
    // -------------------------------------------------------------------------

    private List<TransactionHistoryItemDto> fetchSuccess(
            UUID onlineAccountId, List<UUID> pouchAccountIds, String typeFilter, Instant from, Instant to) {

        List<UUID> viewerAccounts = new ArrayList<>();
        viewerAccounts.add(onlineAccountId);
        viewerAccounts.addAll(pouchAccountIds);

        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT lt.transaction_id, lt.type, lt.reference_id, lt.description, lt.created_at, " +
                "       le_self.direction AS direction, le_self.amount AS amount, " +
                "       u_cp.full_name AS counterparty_name " +
                "FROM ledger_transactions lt " +
                "JOIN ledger_entries le_self ON le_self.transaction_id = lt.transaction_id " +
                "LEFT JOIN ledger_entries le_cp ON le_cp.transaction_id = lt.transaction_id " +
                "                                AND le_cp.account_id <> le_self.account_id " +
                "LEFT JOIN accounts a_cp ON a_cp.account_id = le_cp.account_id " +
                "LEFT JOIN users u_cp ON u_cp.user_id = a_cp.user_id " +
                "WHERE le_self.account_id IN (" + placeholders(viewerAccounts.size()) + ") " +
                // For POUCH_LOAD/POUCH_REFUND both legs belong to the same viewer (their own
                // online + pouch accounts) — keep only the online leg so the transaction isn't
                // reported twice.
                "  AND NOT (lt.type IN ('POUCH_LOAD', 'POUCH_REFUND') AND le_self.account_id <> ?) ");
        args.addAll(viewerAccounts);
        args.add(onlineAccountId);

        if (typeFilter != null && !typeFilter.isBlank()) {
            sql.append(" AND lt.type = ? ");
            args.add(typeFilter);
        }
        if (from != null) {
            sql.append(" AND lt.created_at >= ? ");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND lt.created_at < ? ");
            args.add(Timestamp.from(to));
        }

        return jdbc.query(sql.toString(), (rs, rowNum) -> mapSuccessRow(rs), args.toArray());
    }

    private TransactionHistoryItemDto mapSuccessRow(ResultSet rs) throws SQLException {
        String type = rs.getString("type");
        String counterparty = switch (type) {
            case "TOPUP" -> "System";
            case "POUCH_LOAD", "POUCH_REFUND" -> "Pouch";
            default -> rs.getString("counterparty_name");
        };
        return new TransactionHistoryItemDto(
                rs.getLong("transaction_id"),
                rs.getString("reference_id"),
                type,
                rs.getString("direction"),
                rs.getLong("amount"),
                counterparty,
                TransactionStatus.SUCCESS,
                rs.getString("description"),
                rs.getTimestamp("created_at").toInstant());
    }

    // -------------------------------------------------------------------------
    // PENDING — uploaded, not yet worker-settled (still in sync_inbox)
    // -------------------------------------------------------------------------

    private List<TransactionHistoryItemDto> fetchPending(List<String> deviceIds, Instant from, Instant to) {
        if (deviceIds.isEmpty()) return List.of();

        List<Object> args = new ArrayList<>(deviceIds);
        StringBuilder sql = new StringBuilder(
                "SELECT batch_id, raw_payload::text AS raw_payload, received_at " +
                "FROM sync_inbox " +
                "WHERE device_id IN (" + placeholders(deviceIds.size()) + ") " +
                "  AND status IN ('PENDING', 'PROCESSING') ");
        if (from != null) {
            sql.append(" AND received_at >= ? ");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND received_at < ? ");
            args.add(Timestamp.from(to));
        }

        List<TransactionHistoryItemDto> items = new ArrayList<>();
        jdbc.query(sql.toString(), rs -> {
            UUID batchId = rs.getObject("batch_id", UUID.class);
            Instant receivedAt = rs.getTimestamp("received_at").toInstant();
            SyncBatchRequest batch;
            try {
                batch = objectMapper.readValue(rs.getString("raw_payload"), SyncBatchRequest.class);
            } catch (Exception e) {
                log.warn("Batch {} has unparseable payload; omitting from PENDING history", batchId);
                return;
            }
            for (SyncOfflineTxnRequest txn : batch.transactions() == null ? List.<SyncOfflineTxnRequest>of() : batch.transactions()) {
                items.add(new TransactionHistoryItemDto(
                        null,
                        String.valueOf(txn.offlineTxnId()),
                        "OFFLINE_TRANSFER",
                        "DEBIT",
                        txn.amount(),
                        resolveDeviceOwnerName(txn.receiverDeviceId()),
                        TransactionStatus.PENDING,
                        "Awaiting settlement (batch " + batchId + ")",
                        receivedAt));
            }
        }, args.toArray());
        return items;
    }

    // -------------------------------------------------------------------------
    // FAILED — flagged, never posted
    // -------------------------------------------------------------------------

    private List<TransactionHistoryItemDto> fetchFailed(List<String> deviceIds, Instant from, Instant to) {
        if (deviceIds.isEmpty()) return List.of();

        List<Object> args = new ArrayList<>(deviceIds);
        StringBuilder sql = new StringBuilder(
                "SELECT ft.flag_id, ft.reason, ft.detail, ft.created_at " +
                "FROM flagged_transactions ft " +
                "JOIN sync_inbox si ON si.batch_id = ft.batch_id " +
                "WHERE si.device_id IN (" + placeholders(deviceIds.size()) + ") " +
                "  AND ft.offline_txn_id IS NULL ");
        if (from != null) {
            sql.append(" AND ft.created_at >= ? ");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND ft.created_at < ? ");
            args.add(Timestamp.from(to));
        }

        return jdbc.query(sql.toString(), (rs, rowNum) -> new TransactionHistoryItemDto(
                null,
                "flag-" + rs.getLong("flag_id"),
                "OFFLINE_TRANSFER",
                "DEBIT",
                0L,
                null,
                TransactionStatus.FAILED,
                rs.getString("reason") + ": " + rs.getString("detail"),
                rs.getTimestamp("created_at").toInstant()
        ), args.toArray());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String resolveDeviceOwnerName(String deviceId) {
        if (deviceId == null) return null;
        try {
            return jdbc.queryForObject(
                    "SELECT u.full_name FROM devices d JOIN users u ON u.user_id = d.user_id WHERE d.device_id = ?",
                    String.class, deviceId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }
}
