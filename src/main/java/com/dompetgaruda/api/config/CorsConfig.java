package com.dompetgaruda.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

/**
 * CORS configuration for the backoffice UI (api profile only).
 *
 * <p>Allows browser requests from the origins listed in {@code cors.allowed-origins}
 * (env: {@code CORS_ALLOWED_ORIGINS}, default: {@code http://localhost:3000}).
 *
 * <p>{@code allowCredentials} is {@code false} — the backoffice sends Bearer tokens in the
 * {@code Authorization} header, not cookies, so credential-mode is not required.
 *
 * <p>{@code @Profile("api")} — CORS is meaningless in the worker (no web server).
 */
@Configuration
@Profile("api")
public class CorsConfig {

    @Value("${cors.allowed-origins:http://localhost:3000}")
    private String[] allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.asList(allowedOrigins));
        config.setAllowedMethods(Arrays.asList(
                "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
        ));
        config.setAllowedHeaders(Arrays.asList(
                "Authorization", "Content-Type", "Accept"
        ));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/admin/**", config);
        source.registerCorsConfiguration("/device/**", config);
        return source;
    }
}
