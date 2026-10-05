package com.gs.monolito.auth.config;

import com.gs.monolito.auth.controllers.BotAccesoController;
import com.gs.monolito.auth.service.CustomUserDetailsService;
import com.gs.monolito.common.security.CsrfCookieFilter;
import com.gs.monolito.common.security.CsrfRequestMatchers;
import com.gs.monolito.common.security.JwtCookieAuthenticationFilter;
import com.gs.monolito.common.security.SecurityHeaders;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AccountExpiredException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Security del módulo auth. Antes eran 5 SecurityConfig, uno por microservicio,
 * cada uno dueño de todo su propio filtro; acá cada módulo aporta sus propios
 * `@Order`-ed `SecurityFilterChain` con `.securityMatcher(...)`. El
 * `JwtAuthenticationConverter`/`RSAKey`/`JwtDecoder`/`JwtEncoder` se comparten
 * desde {@link com.gs.monolito.common.security.JwtBeans}.
 *
 * Cambios respecto al SecurityConfig original de ms-auth:
 * - `securityMatcher("/api/auth/**")` explícito en la chain de negocio, para
 *   no interceptar rutas de otros módulos.
 * - Se eliminó `InternalApiKeyFilter` y la regla de `/api/auth/auditoria/ingest`
 *   (ROLE_INTERNAL) — sin uso: ningún otro módulo llama más por HTTP a esa
 *   ingesta, ahora es una llamada directa a AuditoriaService en el mismo proceso.
 * - Se eliminó la regla de `/h2-console/**` y el `frameOptions` asociado — el
 *   monolito no usa H2, siempre MySQL (ver application.properties).
 * - Se eliminó el Authorization Server OAuth2 (chain de /oauth2/**, OIDC y el
 *   client "gs-frontend" con su secreto escrito en el código): nadie lo usaba
 *   — el login real es /api/auth/login — y exponía endpoints con un secreto
 *   que, con el repo público, era público también.
 * - CORS disabled: frontend y API comparten origen detrás de nginx.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class AuthSecurityConfig {

    private final CustomUserDetailsService userDetailsService;

    public AuthSecurityConfig(CustomUserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    // Endpoints de negocio de /api/auth/** — login abierto, gestión de usuarios por rol
    @Bean
    @Order(2)
    public SecurityFilterChain authDomainSecurityFilterChain(HttpSecurity http,
                                                              JwtAuthenticationConverter jwtAuthenticationConverter,
                                                              JwtCookieAuthenticationFilter jwtCookieAuthenticationFilter) throws Exception {
        http
            .securityMatcher("/api/auth/**")
            // Mismo origen vía nginx (frontend y API comparten dominio) — sin CORS.
            .cors(cors -> cors.disable())
            // login y refresh quedan afuera de CSRF: son los únicos POST que pueden
            // ocurrir sin una cookie de sesión válida todavía (refresh corre
            // justo cuando el access token ya venció). Igual de seguro que login
            // sin CSRF: la respuesta (Set-Cookie httpOnly) solo la puede leer el
            // browser legítimo del usuario, nunca el sitio que forzó el POST.
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                // Sin esto Spring BORRA la cookie XSRF-TOKEN en cada GET que ya la trae (el filtro JWT
                // autentica "en este request" y su CsrfAuthenticationStrategy la rota): el navegador se
                // quedaba sin cookie y el logout daba 403. Ver CsrfCookieNoSeBorraWebTest.
                .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
                // bot-acceso tampoco: es un chequeo sin efectos (nginx lo consulta por
                // GET en cada request a /api/bot/**), no una acción que se pueda forzar.
                .requireCsrfProtectionMatcher(CsrfRequestMatchers.requerirSalvo(
                    "/api/auth/login", "/api/auth/refresh", BotAccesoController.RUTA))
            )
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .addFilterBefore(jwtCookieAuthenticationFilter, BearerTokenAuthenticationFilter.class)
            .headers(SecurityHeaders::aplicar)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login").permitAll()
                .requestMatchers("/api/auth/refresh").permitAll()
                .requestMatchers("/api/auth/logout").authenticated()
                // Ver la bitácora — exclusiva de ADMIN
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/auth/auditoria").hasRole("ADMIN")
                // Backup manual (botón "hacer backup ahora") — exclusivo de ADMIN
                .requestMatchers("/api/auth/backup/**").hasRole("ADMIN")
                // Gestión de usuarios (alta, roles, estado, reseteo de contraseña,
                // teléfono) — exclusiva del ADMIN. ADMINISTRATIVO solo puede LEER la
                // lista (la usa Finanzas → Sueldos). AuthController repite el chequeo
                // de ADMIN adentro de cada endpoint (defensa en profundidad).
                .requestMatchers("/api/auth/register").hasRole("ADMIN")
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/auth/usuarios").hasAnyRole("ADMIN", "ADMINISTRATIVO")
                .requestMatchers("/api/auth/usuarios/**").hasRole("ADMIN")
                // Chequeo que usa nginx (auth_request) antes de dejar pasar algo a
                // /api/bot/** — mismos roles que ven la pantalla "Bot WhatsApp".
                .requestMatchers(BotAccesoController.RUTA).hasAnyRole("ADMIN", "ADMINISTRATIVO")
                // /me, /me/password, /me/aceptar-terminos: cualquier usuario logueado
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
        return new ProviderManager(proveedorLogin(userDetailsService, passwordEncoder()));
    }

    /**
     * Proveedor de login que verifica la contraseña ANTES del estado de la cuenta.
     *
     * <p>Por default Spring chequea "deshabilitada/bloqueada" antes de mirar la
     * contraseña: probando usuarios al azar con cualquier clave, la respuesta
     * distinta ("cuenta desactivada" vs "usuario o contraseña incorrectos")
     * revelaba qué cuentas existen. Moviendo esos chequeos a después, quien no
     * sabe la contraseña siempre recibe el mismo "incorrectos".</p>
     *
     * <p>Package-private para testearlo sin levantar el contexto.</p>
     */
    static DaoAuthenticationProvider proveedorLogin(UserDetailsService uds, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(uds);
        provider.setPasswordEncoder(encoder);
        provider.setPreAuthenticationChecks(user -> { });
        provider.setPostAuthenticationChecks(user -> {
            if (!user.isAccountNonLocked())      throw new LockedException("Cuenta bloqueada");
            if (!user.isEnabled())               throw new DisabledException("Cuenta deshabilitada");
            if (!user.isAccountNonExpired())     throw new AccountExpiredException("Cuenta vencida");
            if (!user.isCredentialsNonExpired()) throw new CredentialsExpiredException("Credenciales vencidas");
        });
        return provider;
    }
}
