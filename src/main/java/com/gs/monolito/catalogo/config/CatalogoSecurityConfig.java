package com.gs.monolito.catalogo.config;

import com.gs.monolito.common.security.CsrfCookieFilter;
import com.gs.monolito.common.security.CsrfRequestMatchers;
import com.gs.monolito.common.security.JwtCookieAuthenticationFilter;
import com.gs.monolito.common.security.SecurityHeaders;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

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
                                                            JwtAuthenticationConverter jwtAuthenticationConverter,
                                                            JwtCookieAuthenticationFilter jwtCookieAuthenticationFilter) throws Exception {
        http
            .securityMatcher("/api/catalogo/**")
            .cors(cors -> cors.disable())
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .requireCsrfProtectionMatcher(CsrfRequestMatchers.requerirSalvo())
            )
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .addFilterBefore(jwtCookieAuthenticationFilter, BearerTokenAuthenticationFilter.class)
            .headers(SecurityHeaders::aplicar)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Más específica primero: la config del catálogo público es
                // ADMIN-only en lectura (PUT ya cae en la regla genérica de
                // abajo, que ya exige ADMIN para todo /api/catalogo/** con PUT).
                .requestMatchers(HttpMethod.GET, "/api/catalogo/configuracion-publica").hasRole("ADMIN")
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

    /**
     * Catálogo PÚBLICO (sin login) — ver
     * {@link com.gs.monolito.catalogo.controller.PublicoCatalogoController}.
     * Chain separada (no un permitAll dentro de la de arriba) porque el resto
     * de /api/catalogo/** exige autenticación; acá es exactamente lo opuesto.
     * Solo GET, sin CSRF: no hay ningún endpoint que cambie estado acá.
     */
    @Bean
    @org.springframework.core.annotation.Order(7)
    public SecurityFilterChain publicoCatalogoSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/publico/catalogo/**")
            .cors(cors -> cors.disable())
            .csrf(csrf -> csrf.disable())
            .headers(SecurityHeaders::aplicar)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
