package com.gs.monolito.stock.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/** Security de /api/stock/**. */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class StockSecurityConfig {

    @Bean
    @Order(6)
    public SecurityFilterChain stockSecurityFilterChain(HttpSecurity http,
                                                         JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
            .securityMatcher("/api/stock/**")
            .cors(cors -> cors.disable())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/stock/**")
                    .hasAnyRole("ADMIN", "TECNICO", "ADMINISTRATIVO")
                .requestMatchers(HttpMethod.POST, "/api/stock/movimiento")
                    .hasAnyRole("ADMIN", "TECNICO")
                .requestMatchers(HttpMethod.POST, "/api/stock/**")
                    .hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/stock/**")
                    .hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
            );
        return http.build();
    }
}
