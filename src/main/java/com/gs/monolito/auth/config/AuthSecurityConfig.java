package com.gs.monolito.auth.config;

import com.gs.monolito.auth.service.CustomUserDetailsService;
import com.gs.monolito.common.security.CsrfCookieFilter;
import com.gs.monolito.common.security.CsrfRequestMatchers;
import com.gs.monolito.common.security.JwtCookieAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.util.UUID;

/**
 * Security del módulo auth. Antes eran 5 SecurityConfig, uno por microservicio,
 * cada uno dueño de todo su propio filtro; acá cada módulo aporta sus propios
 * `@Order`-ed `SecurityFilterChain` con `.securityMatcher(...)`, igual al
 * patrón que YA usaba ms-auth internamente con sus 2 chains (Authorization
 * Server + Resource Server) — se extiende ese mismo patrón, no se inventa uno
 * nuevo. El `JwtAuthenticationConverter`/`RSAKey`/`JwtDecoder`/`JwtEncoder` se
 * comparten desde {@link com.gs.monolito.common.security.JwtBeans}.
 *
 * Cambios respecto al SecurityConfig original de ms-auth:
 * - `securityMatcher("/api/auth/**")` explícito en la chain de negocio, para
 *   no interceptar rutas de otros módulos a medida que se agreguen (Etapa 2+).
 * - Se eliminó `InternalApiKeyFilter` y la regla de `/api/auth/auditoria/ingest`
 *   (ROLE_INTERNAL) — sin uso: ningún otro módulo llama más por HTTP a esa
 *   ingesta, ahora es una llamada directa a AuditoriaService en el mismo proceso.
 * - Se eliminó la regla de `/h2-console/**` y el `frameOptions` asociado — el
 *   monolito no usa H2, siempre MySQL (ver application.properties).
 * - CORS sigue disabled por ahora (antes lo manejaba únicamente el gateway);
 *   se agrega un `CorsConfigurationSource` compartido recién en la Etapa 7,
 *   cuando se conecte el frontend real.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class AuthSecurityConfig {

    private final CustomUserDetailsService userDetailsService;

    public AuthSecurityConfig(CustomUserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    // 1. Filtro del Servidor de Autorización OAuth2 (flujo OIDC estándar)
    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
            .oidc(Customizer.withDefaults());
        http.exceptionHandling(ex -> ex
            .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login"))
        ).oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }

    // 2. Endpoints de negocio de /api/auth/** — login abierto, register/usuarios por rol
    @Bean
    @Order(2)
    public SecurityFilterChain authDomainSecurityFilterChain(HttpSecurity http,
                                                              JwtAuthenticationConverter jwtAuthenticationConverter,
                                                              JwtCookieAuthenticationFilter jwtCookieAuthenticationFilter) throws Exception {
        http
            .securityMatcher("/api/auth/**")
            // Mismo origen vía nginx (frontend y API comparten dominio) — sin CORS.
            .cors(cors -> cors.disable())
            // login queda afuera: es el único POST que ocurre antes de que exista
            // la cookie de sesión. El resto de /api/auth/** sí exige el token CSRF
            // (cookie XSRF-TOKEN legible por JS + header X-XSRF-TOKEN) — la propia
            // respuesta del login ya la deja puesta (ver CsrfCookieFilter), así que
            // la siguiente petición mutante (ej. aceptar-terminos) ya la tiene.
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .requireCsrfProtectionMatcher(CsrfRequestMatchers.requerirSalvo("/api/auth/login"))
            )
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .addFilterBefore(jwtCookieAuthenticationFilter, BearerTokenAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login").permitAll()
                .requestMatchers("/api/auth/logout").authenticated()
                // Ver la bitácora — exclusiva de ADMIN
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/auth/auditoria").hasRole("ADMIN")
                // Backup manual (botón "hacer backup ahora") — exclusivo de ADMIN
                .requestMatchers("/api/auth/backup/**").hasRole("ADMIN")
                // Crear usuarios — exclusivo de ADMIN. Separación de poderes a propósito:
                // quien crea la cuenta no puede ser quien la activa (ADMINISTRATIVO,
                // controlado dentro de AuthController, no acá).
                .requestMatchers("/api/auth/register").hasRole("ADMIN")
                .requestMatchers("/api/auth/usuarios/**").hasAnyRole("ADMIN", "ADMINISTRATIVO")
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return new ProviderManager(provider);
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder().build();
    }

    /**
     * RegisteredClientRepository requerido por OAuth2AuthorizationServerConfiguration
     * aunque el login principal sea el custom /api/auth/login. Sin uso real hoy,
     * queda disponible por si el frontend migra al flow OIDC estándar.
     */
    @Bean
    public RegisteredClientRepository registeredClientRepository(PasswordEncoder passwordEncoder) {
        RegisteredClient frontendClient = RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId("gs-frontend")
            .clientSecret(passwordEncoder.encode("gs-frontend-secret"))
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .redirectUri("http://localhost:4200/login/oauth2/code/gs")
            .redirectUri("http://localhost/login/oauth2/code/gs")
            .scope(OidcScopes.OPENID)
            .scope(OidcScopes.PROFILE)
            .clientSettings(ClientSettings.builder()
                .requireAuthorizationConsent(false)
                .requireProofKey(true)
                .build())
            .build();
        return new InMemoryRegisteredClientRepository(frontendClient);
    }
}
