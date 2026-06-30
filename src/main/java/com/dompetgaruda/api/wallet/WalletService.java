package com.dompetgaruda.api.wallet;

import com.dompetgaruda.api.ledger.LedgerEntry;
import com.dompetgaruda.api.ledger.LedgerPostingService;
import com.dompetgaruda.api.ledger.PostingRequest;
import com.dompetgaruda.api.wallet.dto.TopUpRequest;
import com.dompetgaruda.api.wallet.dto.TopUpResponse;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Handles money-in operations for a user's ONLINE ledger account.
 *
 * <p>Posting shape (CLAUDE.md §3):
 * <pre>
 *   TOPUP : DEBIT system → CREDIT user.online
 * </pre>
 * The actual double-entry insert is delegated to {@link LedgerPostingService}, which enforces
 * the balanced-posting invariant and uses plain SQL (CLAUDE.md §2 / §7.2).
 */
@Service
public class WalletService {

    private final LedgerPostingService ledger;

    public WalletService(LedgerPostingService ledger) {
        this.ledger = ledger;
    }

    /**
     * Credits {@code req.amount()} Rupiah to the user's ONLINE account and returns the
     * new ledger-derived balance.
     *
     * @param userId user to top up — must have an existing ONLINE account
     * @param req    validated top-up request
     * @return response containing new balance and the ledger transaction id
     * @throws ResponseStatusException 404 if the user has no ONLINE account
     */
    @Transactional
    public TopUpResponse topUp(UUID userId, TopUpRequest req) {
        UUID onlineAccountId;
        try {
            onlineAccountId = ledger.resolveOnlineAccount(userId);
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No ONLINE account found for user: " + userId);
        }

        long txnId = ledger.post(new PostingRequest(
                "TOPUP",
                "USER",
                userId.toString(),
                req.reference(),
                List.of(
                        new LedgerEntry(ledger.resolveSystemAccount(), "DEBIT",  req.amount()),
                        new LedgerEntry(onlineAccountId,               "CREDIT", req.amount())
                )
        ));

        long newBalance = ledger.getBalance(onlineAccountId);

        return new TopUpResponse(userId, newBalance, txnId, req.reference());
    }
}
