package com.dompetgaruda.api.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.UUID;

/**
 * Manual role check against the authorities {@link AdminTokenFilter} sets from the JWT
 * role claim. No {@code @PreAuthorize}/{@code @EnableMethodSecurity} infra exists yet
 * (CLAUDE.md §14) — this is the first explicit role gate, introduced for the articles feature.
 */
public final class RoleGuard {

    private RoleGuard() {}

    public static void requireAnyRole(Set<String> allowedRoles) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        boolean allowed = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> allowedRoles.stream().anyMatch(role -> authority.equals("ROLE_" + role)));
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Insufficient role");
        }
    }

    public static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(auth.getName());
    }
}
