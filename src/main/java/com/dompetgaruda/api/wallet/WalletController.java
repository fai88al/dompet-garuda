package com.dompetgaruda.api.wallet;

import com.dompetgaruda.api.wallet.dto.TopUpRequest;
import com.dompetgaruda.api.wallet.dto.TopUpResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/admin/users")
@Tag(name = "Wallet — Admin", description = "Admin-only wallet operations: top up a user's online balance.")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    /**
     * POST /admin/users/{userId}/topup
     * Credits the user's ONLINE ledger account. Requires admin Bearer token.
     */
    @PostMapping("/{userId}/topup")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Top up a user's ONLINE balance.",
               description = "Posts a balanced TOPUP ledger entry (DEBIT system → CREDIT user.online). " +
                             "Returns the new ledger-derived balance and the transaction id for audit.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Top-up posted; returns new online balance."),
            @ApiResponse(responseCode = "400", description = "Validation failed — amount must be > 0."),
            @ApiResponse(responseCode = "401", description = "Missing or invalid admin Bearer token."),
            @ApiResponse(responseCode = "404", description = "User has no ONLINE ledger account.")
    })
    public TopUpResponse topUp(
            @PathVariable UUID userId,
            @Valid @RequestBody TopUpRequest request) {
        return walletService.topUp(userId, request);
    }
}
