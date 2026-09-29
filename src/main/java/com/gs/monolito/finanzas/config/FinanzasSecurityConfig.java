package com.gs.monolito.finanzas.config;

import com.gs.monolito.common.security.CsrfCookieFilter;
import com.gs.monolito.common.security.CsrfRequestMatchers;
import com.gs.monolito.common.security.JwtCookieAuthenticationFilter;
import com.gs.monolito.common.security.SecurityHeaders;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
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
 * Security de /api/finanzas/**. Las dos rutas de pago del bot se autentican con
 * {@link BotApiKeyFilter} (el bot no tiene JWT), que deja un principal con
 * ROLE_BOT — un rol que solo sirve para esas dos rutas, nada más.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class FinanzasSecurityConfig {

    private final BotApiKeyFilter botApiKeyFilter;

    public FinanzasSecurityConfig(BotApiKeyFilter botApiKeyFilter) {
        this.botApiKeyFilter = botApiKeyFilter;
    }

    @Bean
    @Order(5)
    public SecurityFilterChain finanzasSecurityFilterChain(HttpSecurity http,
                                                            JwtAuthenticationConverter jwtAuthenticationConverter,
                                                            JwtCookieAuthenticationFilter jwtCookieAuthenticationFilter) throws Exception {
        http
            .securityMatcher("/api/finanzas/**")
            .cors(cors -> cors.disable())
            // El bot llama estas dos rutas server-to-server (X-Bot-Api-Key, sin
            // cookie ni CSRF token posible) — se excluyen de la validación CSRF,
            // no de la autenticación (BotApiKeyFilter sigue exigiendo la key).
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .requireCsrfProtectionMatcher(CsrfRequestMatchers.requerirSalvo(
                    "/api/finanzas/sueldos/pago-automatico",
                    "/api/finanzas/sueldos/pago-efectivo"))
            )
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // El bot se autentica por API key (header X-Bot-Api-Key) antes del JWT:
            // si la key coincide, BotApiKeyFilter deja un principal ROLE_BOT. Sus
            // dos rutas van primero y SOLO aceptan ROLE_BOT (el panel no las usa);
            // y ROLE_BOT no matchea ninguna otra regla de abajo, así que la key
            // del bot no sirve para nada más.
            .addFilterBefore(botApiKeyFilter, BearerTokenAuthenticationFilter.class)
            .addFilterBefore(jwtCookieAuthenticationFilter, BearerTokenAuthenticationFilter.class)
            .headers(SecurityHeaders::aplicar)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, BotApiKeyFilter.RUTAS_BOT.toArray(String[]::new)).hasRole("BOT")
                .requestMatchers("/api/finanzas/cajas/**").hasAnyRole("ADMIN", "ADMINISTRATIVO")
                .requestMatchers("/api/finanzas/reportes/**").hasAnyRole("ADMIN", "ADMINISTRATIVO")
                .requestMatchers("/api/finanzas/sueldos/**").hasAnyRole("ADMIN", "ADMINISTRATIVO")
                .requestMatchers("/api/finanzas/proveedores/**").hasAnyRole("ADMIN", "ADMINISTRATIVO")
                .requestMatchers(HttpMethod.GET, "/api/finanzas/**")
                    .hasAnyRole("ADMIN", "ADMINISTRATIVO")
                .requestMatchers(HttpMethod.POST, "/api/finanzas/comprobantes")
                    .hasAnyRole("ADMIN", "ADMINISTRATIVO")
                .requestMatchers(HttpMethod.PATCH, "/api/finanzas/comprobantes/pedido/*/monto")
                    .hasAnyRole("ADMIN", "ADMINISTRATIVO")
                .requestMatchers(HttpMethod.POST, "/api/finanzas/odontologos/*/pagos")
                    .hasAnyRole("ADMIN", "ADMINISTRATIVO")
                // Fail-closed: lo que no tenga regla explícita arriba, se rechaza.
                .anyRequest().denyAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
            );
        return http.build();
    }
}
