package com.dompetgaruda.api.auth;

import com.dompetgaruda.api.auth.dto.LoginRequest;
import com.dompetgaruda.api.auth.dto.LoginResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * Admin login endpoint (FR15).
 *
 * <p>Accepts username (email) + password, verifies against {@code admin_users} table,
 * and on success issues a signed JWT containing {@code sub}, {@code username}, {@code role}.
 *
 * <p>This endpoint is excluded from {@link AdminTokenFilter} — it must be callable without
 * a token. The filter passes {@code /admin/auth/login} through unconditionally.
 *
 * <p>Same 401 message for unknown username and wrong password to prevent username enumeration
 * (CLAUDE.md §4). Passwords and tokens are never logged (§7.9).
 *
 * <p>{@code @Profile("api")} — {@code ADMIN_JWT_SECRET} is not set in the worker container.
 */
@RestController
@RequestMapping("/admin/auth")
@Profile("api")
@Tag(name = "Admin Auth", description = "Admin authentication — exchange credentials for a JWT.")
public class AdminLoginController {

    private final AdminUserRepository adminUserRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptTracker attemptTracker;

    public AdminLoginController(AdminUserRepository adminUserRepository,
                                BCryptPasswordEncoder passwordEncoder,
                                JwtService jwtService,
                                LoginAttemptTracker attemptTracker) {
        this.adminUserRepository = adminUserRepository;
        this.passwordEncoder     = passwordEncoder;
        this.jwtService          = jwtService;
        this.attemptTracker      = attemptTracker;
    }

    @PostMapping("/login")
    @Operation(
            summary = "Admin login (FR15)",
            description = "Validates email + password against admin_users and returns a signed JWT. " +
                          "After 5 failed attempts from the same IP within 5 minutes, returns 429. " +
                          "Same 401 message for unknown username and wrong password (no enumeration). " +
                          "Credentials and tokens are never logged (§7.9).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Credentials valid — JWT returned.",
                    content = @Content(schema = @Schema(implementation = LoginResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed — username or password blank."),
            @ApiResponse(responseCode = "401", description = "Invalid username or password."),
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

        Optional<AdminUser> userOpt = adminUserRepository.findByUsername(request.username());
        if (userOpt.isPresent() && passwordEncoder.matches(request.password(), userOpt.get().getPasswordHash())) {
            attemptTracker.recordSuccess(ip);
            AdminUser user = userOpt.get();
            String token = jwtService.issue(user.getId(), user.getUsername(), user.getRole());
            return ResponseEntity.ok(new LoginResponse(token, "Bearer", user.getUsername(), user.getRole()));
        }

        attemptTracker.recordFailure(ip);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("message", "Invalid username or password"));
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }
}
