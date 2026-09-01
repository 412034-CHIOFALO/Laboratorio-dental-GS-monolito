package com.gs.monolito.common.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resuelve la IP real del cliente detrás de nginx. Sin esto, request.getRemoteAddr()
 * siempre devuelve la IP interna del contenedor de nginx (el único que le habla
 * directo al monolito), no la del navegador — inútil para rate limiting o auditoría.
 */
public final class ClientIp {

    private ClientIp() {}

    public static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }
        return request.getRemoteAddr();
    }
}
