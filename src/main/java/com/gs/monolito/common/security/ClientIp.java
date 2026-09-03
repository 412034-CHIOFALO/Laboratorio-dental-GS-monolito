package com.gs.monolito.common.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resuelve la IP real del cliente detrás de nginx. Sin esto, request.getRemoteAddr()
 * siempre devuelve la IP interna del contenedor de nginx (el único que le habla
 * directo al monolito), no la del navegador — inútil para rate limiting o auditoría.
 * <p>
 * Toma el ÚLTIMO valor de X-Forwarded-For, no el primero: nginx lo arma con
 * {@code proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for}, que
 * AGREGA la IP real al final de lo que ya venía en el header — cualquiera que
 * le hable directo a nginx puede mandar su propio X-Forwarded-For con lo que
 * quiera adelante. Si acá se toma el primero (el que venía "de afuera"), un
 * atacante rota ese valor en cada request y esquiva por completo el rate
 * limit de login (cada IP falsa arranca su propio bucket) y ensucia la
 * auditoría de intentos fallidos con IPs inventadas. El último valor es
 * siempre el que nginx mismo agregó — el único en el que se puede confiar.
 */
public final class ClientIp {

    private ClientIp() {}

    public static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] partes = forwarded.split(",");
            return partes[partes.length - 1].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }
        return request.getRemoteAddr();
    }
}
