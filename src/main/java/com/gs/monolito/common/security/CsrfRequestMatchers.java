package com.gs.monolito.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.Set;

/**
 * Matcher de "requiere CSRF" para reemplazar el default de cada chain.
 *
 * <p>Necesario porque {@code OAuth2ResourceServerConfigurer} exime automáticamente
 * de CSRF cualquier request que su {@code BearerTokenResolver} reconozca como
 * portador de un token — pensado para el caso header-only (un atacante no
 * puede forzar al browser de la víctima a mandar un Authorization header
 * arbitrario). Como acá el JWT viaja en una cookie que el browser SÍ adjunta
 * solo, esa exención automática deja el sistema expuesto a CSRF de nuevo si
 * no se reemplaza el matcher entero — un simple {@code ignoringRequestMatchers}
 * no alcanza porque se combina con la exención automática en vez de anularla
 * (confirmado con logging TRACE de Spring Security: el matcher final incluía
 * "OR BearerTokenRequestMatcher" pese a no haberlo pedido).</p>
 */
public final class CsrfRequestMatchers {

    private static final Set<String> METODOS_SEGUROS = Set.of("GET", "HEAD", "TRACE", "OPTIONS");

    private CsrfRequestMatchers() {}

    /** Exige CSRF para cualquier método mutante, salvo los paths exactos dados (ej. login, bot). */
    public static RequestMatcher requerirSalvo(String... pathsExentos) {
        Set<String> exentos = Set.of(pathsExentos);
        return (HttpServletRequest request) -> {
            if (METODOS_SEGUROS.contains(request.getMethod())) return false;
            return !exentos.contains(request.getRequestURI());
        };
    }
}
