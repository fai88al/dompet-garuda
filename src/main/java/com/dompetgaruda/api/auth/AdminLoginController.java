package com.dompetgaruda.api.auth;

import com.dompetgaruda.api.auth.dto.LoginRequest;
import com.dompetgaruda.api.auth.dto.LoginResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * Admin login endpoint (FR15).
 *
 * <p>Accepts the admin password and, if it matches {@code ADMIN_API_TOKEN}, returns that token
 * so the backoffice UI can use it as a Bearer token on subsequent admin requests. The token IS
 * the existing {@code ADMIN_API_TOKEN} — no separate token is created (CLAUDE.md §12).
 *
 * <p>This endpoint is excluded from the {@link AdminTokenFilter} bearer-token check so that
 * it can be called without a token. It validates the password itself.
 *
 * <p>Brute-force protection: after {@link LoginAttemptTracker#MAX_ATTEMPTS} consecutive
 * failures from the same IP within a {@link LoginAttemptTracker#WINDOW_MINUTES}-minute window,
 * the endpoint returns 429 until the window expires.
 *
 * <p>Rule §7.9: the password and token value are <strong>never logged</strong>.
 *
 * <p>{@code @Profile("api")} — {@code ADMIN_API_TOKEN} is not set in the worker container.
 */
@RestController
@RequestMapping("/admin/auth")
@Profile("api")
@Tag(name = "Admin Auth", description = "Admin authentication — exchange password for Bearer token.")
public class AdminLoginController {

    private final byte[] expectedHash;
    private final String adminApiToken;
    private final LoginAttemptTracker attemptTracker;

    public AdminLoginController(
            @Value("${admin.api-token}") String adminApiToken,
            LoginAttemptTracker attemptTracker) {
        // Pre-hash the token so comparison never touches the plaintext at call time
        this.expectedHash  = sha256(adminApiToken.getBytes(StandardCharsets.UTF_8));
        this.adminApiToken = adminApiToken;
        this.attemptTracker = attemptTracker;
    }

    @PostMapping("/login")
    @Operation(
            summary = "Admin login (FR15)",
            description = "Validates the admin password and returns the ADMIN_API_TOKEN to be used as " +
                          "a Bearer token. After 5 failed attempts from the same IP within 5 minutes, " +
                          "returns 429 until the window resets. Password and token are never logged (§7.9).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Password correct — token returned."),
            @ApiResponse(responseCode = "400", description = "Validation failed — password field missing or blank."),
            @ApiResponse(responseCode = "401", description = "Wrong password."),
            @ApiResponse(responseCode = "429", description = "Too many failed attempts from this IP.")
    })
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {

        String ip = resolveClientIp(httpRequest);

        if (attemptTracker.isBlocked(ip)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("message", "Too many failed attempts. Try again in "
                            + LoginAttemptTracker.WINDOW_MINUTES + " minutes."));
        }

        // Constant-time comparison to prevent timing side-channel attacks
        byte[] provided = sha256(request.password().getBytes(StandardCharsets.UTF_8));
        if (MessageDigest.isEqual(expectedHash, provided)) {
            attemptTracker.recordSuccess(ip);
            // Rule §7.9: token value is never logged
            return ResponseEntity.ok(new LoginResponse(adminApiToken, "Bearer"));
        }

        attemptTracker.recordFailure(ip);
        // Rule §7.9: never include the expected token in the error response
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("message", "Invalid password"));
    }

    /**
     * Extracts the real client IP. Checks {@code X-Forwarded-For} first (set by Caddy),
     * falls back to the direct connection address.
     */
    private static String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
