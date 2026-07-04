package com.dompetgaruda.api.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;

/**
 * Enforces admin Bearer-token authentication on all /admin/** paths.
 *
 * The token is compared by comparing SHA-256 hashes to avoid timing attacks
 * on the raw string. Prototype-grade per CLAUDE.md §4 / PRD NG1.
 *
 * The plaintext token is never logged. See CLAUDE.md §7 rule 9.
 *
 * @Profile("api") — ADMIN_API_TOKEN is not set in the worker container; this
 * bean must never be instantiated there.
 */
@Component
@Profile("api")
public class AdminTokenFilter extends OncePerRequestFilter {

    private final byte[] expectedTokenHash;

    public AdminTokenFilter(@Value("${admin.api-token}") String adminToken) {
        this.expectedTokenHash = sha256(adminToken.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/admin/")) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String provided = header.substring(7);
            byte[] providedHash = sha256(provided.getBytes(StandardCharsets.UTF_8));
            if (MessageDigest.isEqual(expectedTokenHash, providedHash)) {
                chain.doFilter(request, response);
                return;
            }
        }

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Unauthorized\"}");
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
