package com.gs.monolito.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Autentica desde la cookie httpOnly de sesión, ANTES de que corra
 * BearerTokenAuthenticationFilter (Resource Server) — a propósito no se hace
 * vía un {@code BearerTokenResolver} personalizado registrado en
 * {@code oauth2ResourceServer()}: Spring exime automáticamente de CSRF
 * cualquier request donde ese resolver "encuentre" un token, sin importar de
 * dónde lo sacó (pensado para el caso header-only, inmune a CSRF por diseño;
 * roto si el token en realidad viaja en una cookie que el browser adjunta
 * solo). Confirmado con logging TRACE: un {@code bearerTokenResolver} leyendo
 * de cookie deja el matcher final en
 * "AND NOT(BearerTokenRequestMatcher)" pase lo que pase, anulando cualquier
 * protección CSRF configurada encima.
 *
 * <p>Con este filtro, Resource Server nunca ve la cookie — solo sigue
 * mirando el header Authorization (vía su resolver default), que sigue
 * siendo legítimamente inmune a CSRF y queda disponible para curl/Swagger/
 * herramientas. La cookie autentica acá, antes, sin disparar esa exención.</p>
 */
public class JwtCookieAuthenticationFilter extends OncePerRequestFilter {

    public static final String COOKIE_NAME = "gs_session";

    private final JwtDecoder jwtDecoder;
    private final JwtAuthenticationConverter jwtAuthenticationConverter;

    public JwtCookieAuthenticationFilter(JwtDecoder jwtDecoder, JwtAuthenticationConverter jwtAuthenticationConverter) {
        this.jwtDecoder = jwtDecoder;
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = leerCookie(request);
            if (token != null) {
                try {
                    Jwt jwt = jwtDecoder.decode(token);
                    // Defensa en profundidad: un refresh token (típ=refresh, ver
                    // AuthController) está firmado con la misma clave y decodifica
                    // bien acá también — sin este chequeo, alguien podría pisar la
                    // cookie de sesión con uno y autenticarse igual, mucho más
                    // tiempo del que debería (el refresh dura semanas, no horas).
                    if (!"refresh".equals(jwt.getClaimAsString("typ"))) {
                        AbstractAuthenticationToken auth = jwtAuthenticationConverter.convert(jwt);
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    }
                } catch (JwtException e) {
                    // Cookie vencida/inválida — seguimos sin autenticar; cae a 401 más adelante.
                }
            }
        }
        chain.doFilter(request, response);
    }

    private String leerCookie(HttpServletRequest request) {
        return CookieUtil.leer(request, COOKIE_NAME);
    }
}
