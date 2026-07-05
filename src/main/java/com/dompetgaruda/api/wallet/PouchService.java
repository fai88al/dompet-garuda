package com.dompetgaruda.api.wallet;

import com.dompetgaruda.api.common.entity.Device;
import com.dompetgaruda.api.ledger.LedgerEntry;
import com.dompetgaruda.api.ledger.LedgerPostingService;
import com.dompetgaruda.api.ledger.PostingRequest;
import com.dompetgaruda.api.wallet.dto.PouchLoadRequest;
import com.dompetgaruda.api.wallet.dto.PouchLoadResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Handles pouch provisioning for FR3/FR13.
 *
 * <p>Posting shape (CLAUDE.md §3):
 * <pre>
 *   POUCH_LOAD : DEBIT user.online → CREDIT device.pouch
 * </pre>
 * Both the ledger posting and the offline_certificates insert happen in one
 * DB transaction — either both commit or neither does (CLAUDE.md §7 rule 2).
 *
 * <p>{@code @Profile("api")} required: injects {@code server.signing-key} which
 * is not set in the worker container (CLAUDE.md §3 profile isolation rule).
 */
@Service
@Profile("api")
public class PouchService {

    private static final long CERT_TTL_HOURS = 24L;

    private final LedgerPostingService ledger;
    private final JdbcTemplate jdbc;
    private final long maxAmountIdr;
    private final PrivateKey signingKey;

    public PouchService(
            LedgerPostingService ledger,
            JdbcTemplate jdbc,
            @Value("${pouch.max-amount-idr}") long maxAmountIdr,
            @Value("${server.signing-key}") String base64SigningKeySeed) {
        this.ledger       = ledger;
        this.jdbc         = jdbc;
        this.maxAmountIdr = maxAmountIdr;
        this.signingKey   = loadPrivateKey(base64SigningKeySeed);
    }

    /**
     * Loads funds from the user's ONLINE account into the device's POUCH and
     * issues a signed offline certificate in a single DB transaction.
     *
     * @param device the authenticated device
     * @param req    validated request — amount is pre-checked by @Min(1)
     * @return the issued certificate details
     * @throws ResponseStatusException 400 if amount exceeds pouch max
     * @throws ResponseStatusException 409 if device already has an ACTIVE certificate
     * @throws ResponseStatusException 422 if online balance is insufficient
     */
    @Transactional
    public PouchLoadResponse load(Device device, PouchLoadRequest req) {
        long amount = req.amount();

        if (amount > maxAmountIdr) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Amount exceeds maximum pouch limit of " + maxAmountIdr + " IDR");
        }

        Integer activeCerts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM offline_certificates WHERE device_id = ? AND status = 'ACTIVE'",
                Integer.class,
                device.getDeviceId());
        if (activeCerts != null && activeCerts > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Device already has an active offline certificate");
        }

        UUID onlineAccountId = ledger.resolveOnlineAccount(device.getUserId());
        long onlineBalance   = ledger.getBalance(onlineAccountId);
        if (amount > onlineBalance) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Insufficient online balance: available=" + onlineBalance + ", requested=" + amount);
        }

        UUID pouchAccountId = ledger.resolvePouchAccount(device.getDeviceId());
        UUID certificateId  = UUID.randomUUID();
        Instant expiresAt   = Instant.now().plus(CERT_TTL_HOURS, ChronoUnit.HOURS);
        String signature    = sign(certificateId, device.getDeviceId(), amount, expiresAt);

        // POUCH_LOAD: DEBIT user.ONLINE → CREDIT device.POUCH (CLAUDE.md §3)
        ledger.post(new PostingRequest(
                "POUCH_LOAD",
                "CERTIFICATE",
                certificateId.toString(),
                "Pouch load for certificate " + certificateId,
                List.of(
                        new LedgerEntry(onlineAccountId, "DEBIT",  amount),
                        new LedgerEntry(pouchAccountId,  "CREDIT", amount)
                )
        ));

        jdbc.update(
                "INSERT INTO offline_certificates " +
                "(certificate_id, device_id, pouch_account_id, issued_amount, server_signature, expires_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
                certificateId,
                device.getDeviceId(),
                pouchAccountId,
                amount,
                signature,
                Timestamp.from(expiresAt));

        return new PouchLoadResponse(certificateId, amount, expiresAt, signature);
    }

    // -------------------------------------------------------------------------
    // Ed25519 signing
    // -------------------------------------------------------------------------

    private String sign(UUID certificateId, UUID deviceId, long issuedAmount, Instant expiresAt) {
        // Canonical message: fields joined by '|' — deterministic and human-readable.
        String message = certificateId + "|" + deviceId + "|" + issuedAmount + "|" + expiresAt.getEpochSecond();
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(signingKey);
            sig.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            throw new IllegalStateException("Failed to sign certificate", e);
        }
    }

    /**
     * Reconstructs an Ed25519 {@link PrivateKey} from a base64-encoded 32-byte seed.
     * The PKCS8 wrapper for Ed25519 is a fixed 16-byte header followed by the seed.
     */
    private static PrivateKey loadPrivateKey(String base64Seed) {
        try {
            byte[] seed = Base64.getDecoder().decode(base64Seed);
            if (seed.length != 32) {
                throw new IllegalArgumentException(
                        "server.signing-key must be a base64-encoded 32-byte Ed25519 seed, got " + seed.length + " bytes");
            }
            // PKCS8 header for Ed25519 OID 1.3.101.112
            byte[] pkcs8Header = { 0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06,
                                   0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20 };
            byte[] pkcs8 = Arrays.copyOf(pkcs8Header, 48);
            System.arraycopy(seed, 0, pkcs8, 16, 32);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(pkcs8);
            return KeyFactory.getInstance("Ed25519").generatePrivate(spec);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to load server signing key", e);
        }
    }
}
