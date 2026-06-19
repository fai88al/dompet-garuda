package com.dompetgaruda.api.auth;

import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Generates device API tokens and computes their SHA-256 hashes.
 *
 * Token format: 32 secure-random bytes encoded as 64-char lowercase hex.
 * Stored hash:  SHA-256(those same 32 bytes) encoded as 64-char lowercase hex.
 *
 * Only the hash is persisted. The plaintext token is returned once at
 * registration and never logged. See CLAUDE.md §4 and §7 rule 9.
 */
@Component
public class DeviceTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Returns a new (plaintext-token, sha256-hash) pair. */
    public TokenPair generate() {
        byte[] rawBytes = new byte[32];
        SECURE_RANDOM.nextBytes(rawBytes);
        String token = HexFormat.of().formatHex(rawBytes);
        String hash  = sha256Hex(rawBytes);
        return new TokenPair(token, hash);
    }

    /** Hashes an arbitrary raw token string as UTF-8 bytes (for verification path). */
    public String hashToken(String rawToken) {
        return sha256Hex(HexFormat.of().parseHex(rawToken));
    }

    private static String sha256Hex(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java spec — this can never happen.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record TokenPair(String token, String hash) {}
}
