package com.gs.monolito.catalogo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security de /api/catalogo/**. El JwtAuthenticationConverter es el
 * compartido de {@link com.gs.monolito.common.security.JwtBeans} (antes cada
 * microservicio tenía el suyo, idéntico).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class CatalogoSecurityConfig {

    @Bean
    @org.springframework.core.annotation.Order(3)
    public SecurityFilterChain catalogoSecurityFilterChain(HttpSecurity http,
                                                            JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
            .securityMatcher("/api/catalogo/**")
            .cors(cors -> cors.disable())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/catalogo", "/api/catalogo/**").authenticated()
                .requestMatchers(HttpMethod.POST,   "/api/catalogo/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT,    "/api/catalogo/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/catalogo/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
            );
        return http.build();
    }
}
