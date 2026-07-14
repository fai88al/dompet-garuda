package com.dompetgaruda.api.config;

import com.dompetgaruda.api.auth.AdminTokenFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
@Profile("api")
public class SecurityConfig {

    private final AdminTokenFilter adminTokenFilter;
    private final CorsConfigurationSource corsConfigurationSource;

    public SecurityConfig(AdminTokenFilter adminTokenFilter,
                          CorsConfigurationSource corsConfigurationSource) {
        this.adminTokenFilter = adminTokenFilter;
        this.corsConfigurationSource = corsConfigurationSource;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(adminTokenFilter, UsernamePasswordAuthenticationFilter.class)
                // AdminTokenFilter handles /admin/** auth directly by returning 401.
                // Everything else (actuator health, future device endpoints) is open here;
                // device-endpoint auth will be added when the device filter is wired in a later PR.
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
