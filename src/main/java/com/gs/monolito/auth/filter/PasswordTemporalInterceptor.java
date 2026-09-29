package com.gs.monolito.auth.filter;

import com.gs.monolito.auth.controllers.AuthController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Mientras la sesión tenga una contraseña temporal pendiente (claim
 * {@code pwd_temporal}, ver AuthController), el único uso permitido de la API
 * es el propio perfil: cambiar la contraseña, aceptar términos y salir — las
 * rutas excluidas se configuran en AuthWebConfig.
 *
 * <p>Antes esto solo lo hacía cumplir el frontend: quien conociera la
 * contraseña temporal podía usar la API directo y no cambiarla nunca. Responde
 * 403 con {@code codigo=DEBE_CAMBIAR_PASSWORD} para que el frontend lleve al
 * usuario a "Mi perfil" en vez de a la pantalla de "sin permisos".</p>
 */
public class PasswordTemporalInterceptor implements HandlerInterceptor {

    public static final String CODIGO = "DEBE_CAMBIAR_PASSWORD";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth
                && Boolean.TRUE.equals(jwtAuth.getToken().getClaim(AuthController.CLAIM_PASSWORD_TEMPORAL))) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"codigo\": \"" + CODIGO + "\", "
                + "\"error\": \"Tenés que cambiar la contraseña temporal antes de seguir.\"}");
            return false;
        }
        return true;
    }
}
