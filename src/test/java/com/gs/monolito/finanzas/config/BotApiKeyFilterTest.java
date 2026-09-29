package com.gs.monolito.finanzas.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La API key del bot solo autentica en sus dos rutas exactas, y con un rol
 * propio (ROLE_BOT) — nunca con los permisos del ADMIN.
 */
class BotApiKeyFilterTest {

    private static final String KEY = "bot-key-de-prueba-0001";
    private final BotApiKeyFilter filter = new BotApiKeyFilter();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(filter, "botApiKey", KEY);
    }

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private Authentication filtrar(String uri, String key) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        if (key != null) req.addHeader("X-Bot-Api-Key", key);
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void keyValidaEnRutaDelBot_autenticaConRolBotYNoAdmin() throws Exception {
        Authentication auth = filtrar("/api/finanzas/sueldos/pago-automatico", KEY);

        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_BOT");
    }

    @Test
    void keyValidaEnUnaRutaQueSoloTerminaIgual_noAutentica() throws Exception {
        // Antes alcanzaba con endsWith(...).
        assertThat(filtrar("/api/finanzas/otra/api/finanzas/sueldos/pago-automatico", KEY)).isNull();
    }

    @Test
    void keyValidaEnCualquierOtraRuta_noAutentica() throws Exception {
        assertThat(filtrar("/api/finanzas/cajas/movimiento", KEY)).isNull();
    }

    @Test
    void keyIncorrectaOAusente_noAutentica() throws Exception {
        assertThat(filtrar("/api/finanzas/sueldos/pago-efectivo", "otra-key")).isNull();
        assertThat(filtrar("/api/finanzas/sueldos/pago-efectivo", null)).isNull();
    }
}
