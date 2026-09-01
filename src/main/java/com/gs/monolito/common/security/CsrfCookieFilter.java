package com.gs.monolito.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Fuerza la carga del CsrfToken en cada request para que
 * CookieCsrfTokenRepository escriba la cookie XSRF-TOKEN en la respuesta.
 * Spring Security 6 difiere la generación del token hasta que algo lo "usa"
 * — sin esto, la cookie nunca aparece y el SPA no tiene nada que devolver
 * en el header X-XSRF-TOKEN. Receta oficial de Spring para SPAs stateless.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken();
        }
        filterChain.doFilter(request, response);
    }
}
