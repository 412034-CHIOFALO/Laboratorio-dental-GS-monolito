package com.gs.monolito.auth.filter;

import com.gs.monolito.auth.controllers.AuthController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Con contraseña temporal pendiente, la API responde 403 con un código que el frontend reconoce. */
class PasswordTemporalInterceptorTest {

    private final PasswordTemporalInterceptor interceptor = new PasswordTemporalInterceptor();

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private void sesion(boolean passwordTemporal) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("tecnico1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (passwordTemporal) jwt.claim(AuthController.CLAIM_PASSWORD_TEMPORAL, true);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt.build()));
    }

    @Test
    void conPasswordTemporal_bloqueaConCodigoParaElFrontend() throws Exception {
        sesion(true);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean sigue = interceptor.preHandle(new MockHttpServletRequest("GET", "/api/pedidos"), resp, new Object());

        assertThat(sigue).isFalse();
        assertThat(resp.getStatus()).isEqualTo(403);
        assertThat(resp.getContentAsString()).contains(PasswordTemporalInterceptor.CODIGO);
    }

    @Test
    void sinPasswordTemporal_dejaPasar() throws Exception {
        sesion(false);

        assertThat(interceptor.preHandle(new MockHttpServletRequest("GET", "/api/pedidos"),
                new MockHttpServletResponse(), new Object())).isTrue();
    }
}
