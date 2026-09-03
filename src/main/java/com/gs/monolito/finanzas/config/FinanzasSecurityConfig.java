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
 * Security de /api/finanzas/**. Conserva el {@link BotApiKeyFilter} (el bot no
 * tiene JWT) y el `permitAll` explícito de las dos rutas de pago del bot, que
 * antes lo garantizaba el gateway antes de llegar acá — ahora que no hay
 * gateway, hace falta declararlo en esta misma chain.
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
            // El bot se autentica por API key (header X-Bot-Api-Key) antes del JWT.
            // BotApiKeyFilter corre antes de esta authorization check y, si la key
            // coincide, deja un principal ROLE_ADMIN ya autenticado en el contexto
            // — por eso pago-automatico puede pedir hasRole("ADMIN") normal (nunca
            // dependió del gateway para esto) y pago-efectivo ni siquiera necesita
            // una regla explícita, cae en anyRequest().authenticated() más abajo.
            .addFilterBefore(botApiKeyFilter, BearerTokenAuthenticationFilter.class)
            .addFilterBefore(jwtCookieAuthenticationFilter, BearerTokenAuthenticationFilter.class)
            .headers(SecurityHeaders::aplicar)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/finanzas/sueldos/pago-automatico").hasRole("ADMIN")
                .requestMatchers("/api/finanzas/cajas/**").hasAnyRole("ADMIN", "ADMINISTRATIVO")
                .requestMatchers("/api/finanzas/reportes/**").hasAnyRole("ADMIN", "ADMINISTRATIVO")
                .requestMatchers("/api/finanzas/sueldos/**").hasAnyRole("ADMIN", "ADMINISTRATIVO")
                .requestMatchers("/api/finanzas/proveedores/**").hasAnyRole("ADMIN", "ADMINISTRATIVO")
                .requestMatchers(HttpMethod.GET, "/api/finanzas/**")
                    .hasAnyRole("ADMIN", "ADMINISTRATIVO")
                .requestMatchers(HttpMethod.POST, "/api/finanzas/comprobantes")
                    .hasAnyRole("ADMIN", "ADMINISTRATIVO")
                .requestMatchers(HttpMethod.PATCH, "/api/finanzas/comprobantes/*/cobrar")
                    .hasRole("ADMIN")
                .requestMatchers(HttpMethod.PATCH, "/api/finanzas/comprobantes/pedido/*/monto")
                    .hasAnyRole("ADMIN", "ADMINISTRATIVO")
                .requestMatchers(HttpMethod.POST, "/api/finanzas/odontologos/*/pagos")
                    .hasAnyRole("ADMIN", "ADMINISTRATIVO")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
            );
        return http.build();
    }
}
