package com.gs.monolito.common.security;

import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Headers de seguridad comunes a las 7 SecurityFilterChain del monolito (una
 * por módulo + la del Authorization Server + la de actuator/swagger). nginx
 * ya manda estos mismos headers para todo lo que sirve (ver
 * nginx.https.conf.template) — esto es la misma protección un nivel más
 * adentro, para no depender solo del proxy (defensa en profundidad, y cubre
 * el caso de pegarle al backend directo en algún otro contexto de deploy).
 * <p>
 * frameOptions/contentTypeOptions ya vienen prendidos por default en Spring
 * Security 6 — acá solo se explicita HSTS (para que el maxAge coincida con
 * el de nginx) y se agrega Referrer-Policy + CSP, que Spring NO manda por
 * default. La CSP es más estricta que la de nginx a propósito: lo único que
 * este backend sirve como HTML es Swagger UI (assets propios, sin fonts/CDNs
 * externos) — la SPA real (que sí carga Google Fonts e imágenes de Unsplash)
 * la sirve nginx con su propia CSP, más permisiva donde hace falta.
 */
public final class SecurityHeaders {

    private static final String CSP =
        "default-src 'self'; " +
        "script-src 'self'; " +
        "style-src 'self' 'unsafe-inline'; " +
        "img-src 'self' data:; " +
        "font-src 'self'; " +
        "connect-src 'self'; " +
        "frame-ancestors 'self'; " +
        "object-src 'none'; " +
        "base-uri 'self'; " +
        "form-action 'self'";

    private SecurityHeaders() {}

    public static void aplicar(HeadersConfigurer<?> headers) {
        headers
            .httpStrictTransportSecurity(hsts -> hsts
                .includeSubDomains(true)
                .maxAgeInSeconds(63072000))
            .referrerPolicy(referrer -> referrer
                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
            .contentSecurityPolicy(csp -> csp.policyDirectives(CSP));
    }
}
