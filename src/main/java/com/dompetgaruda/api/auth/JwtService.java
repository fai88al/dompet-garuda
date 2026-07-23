package com.dompetgaruda.api.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Issues and verifies admin JWTs (FR15, CLAUDE.md §4).
 *
 * <p>Signed with HMAC-SHA256 using a 32-byte key derived from {@code ADMIN_JWT_SECRET}
 * (hex-encoded env var). Token contains {@code sub} (userId), {@code username}, {@code role},
 * {@code iat}, {@code exp} (24 h). Secret is never logged (§7.9).
 *
 * <p>{@code @Profile("api")} — {@code ADMIN_JWT_SECRET} is not set in the worker container.
 */
@Service
@Profile("api")
public class JwtService {

    private static final long EXPIRY_MS = 24L * 60 * 60 * 1000;

    private final SecretKey key;

    public JwtService(@Value("${admin.jwt-secret}") String hexSecret) {
        this.key = Keys.hmacShaKeyFor(HexFormat.of().parseHex(hexSecret));
    }

    public String issue(UUID userId, String username, String role) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", username)
                .claim("role", role)
                .issuedAt(new Date(now))
                .expiration(new Date(now + EXPIRY_MS))
                .signWith(key)
                .compact();
    }

    /** @throws io.jsonwebtoken.JwtException if signature invalid, expired, or malformed */
    public Claims verify(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
