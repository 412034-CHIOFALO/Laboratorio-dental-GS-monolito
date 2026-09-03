package com.gs.monolito.common.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/** Lectura de cookies compartida entre {@link JwtCookieAuthenticationFilter} y AuthController. */
public final class CookieUtil {

    private CookieUtil() {}

    public static String leer(HttpServletRequest request, String nombre) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (nombre.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
