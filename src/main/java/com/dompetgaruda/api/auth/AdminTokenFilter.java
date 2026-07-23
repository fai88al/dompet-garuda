package com.dompetgaruda.api.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Enforces admin Bearer-JWT authentication on all /admin/** paths (FR15).
 *
 * <p>Passes /admin/auth/login through without a token — that endpoint authenticates itself.
 * For all other /admin/** paths: extracts the Bearer token, verifies signature + expiry via
 * {@link JwtService}, and on success sets the authenticated principal in the security context.
 *
 * <p>On any failure (missing, malformed, expired, invalid signature): returns 401.
 * The JWT payload is never logged (§7.9).
 *
 * <p>{@code @Profile("api")} — {@code ADMIN_JWT_SECRET} is not available in the worker container.
 */
@Component
@Profile("api")
public class AdminTokenFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public AdminTokenFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/admin/")) {
            chain.doFilter(request, response);
            return;
        }

        // Login endpoint authenticates via username+password — no Bearer token required
        if ("/admin/auth/login".equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = jwtService.verify(token);
                String role = claims.get("role", String.class);
                var auth = new UsernamePasswordAuthenticationToken(
                        claims.getSubject(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
                chain.doFilter(request, response);
                return;
            } catch (JwtException ignored) {
                // Falls through to 401 below
            }
        }

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Unauthorized\"}");
    }
}
