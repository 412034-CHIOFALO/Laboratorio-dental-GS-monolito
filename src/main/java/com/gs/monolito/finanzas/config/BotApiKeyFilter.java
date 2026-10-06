package com.gs.monolito.finanzas.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Autenticación por API key para el bot de WhatsApp en los endpoints de
 * pago automático (el bot no tiene JWT). Aplica solo a
 * {@code /sueldos/pago-automatico} y {@code /sueldos/pago-efectivo}; el resto
 * de la API sigue protegido por JWT normal.
 *
 * <p>Deja un principal con ROLE_BOT (antes era ROLE_ADMIN: la key del bot daba
 * todos los permisos del administrador durante esa request) y compara la ruta
 * exacta (antes alcanzaba con que la URL <em>terminara</em> en una de ellas).</p>
 */
@Component
public class BotApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Bot-Api-Key";
    static final Set<String> RUTAS_BOT = Set.of(
            "/api/finanzas/sueldos/pago-automatico",
            "/api/finanzas/sueldos/pago-efectivo",
            "/api/finanzas/sueldos/registros-bot/conocidos");

    @Value("${gs.bot.api-key:}")
    private String botApiKey;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (RUTAS_BOT.contains(request.getRequestURI())) {
            String key = request.getHeader(HEADER);
            if (claveValida(key)) {
                var auth = new UsernamePasswordAuthenticationToken(
                        "gs-bot", null,
                        List.of(new SimpleGrantedAuthority("ROLE_BOT")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * Compara la API key en tiempo constante (MessageDigest.isEqual) para no
     * filtrar información por timing. Si no hay key configurada, siempre rechaza.
     */
    private boolean claveValida(String key) {
        if (key == null || botApiKey == null || botApiKey.isBlank()) return false;
        return java.security.MessageDigest.isEqual(
                botApiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
