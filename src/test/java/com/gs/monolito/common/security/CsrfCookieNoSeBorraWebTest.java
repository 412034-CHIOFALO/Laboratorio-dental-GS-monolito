package com.gs.monolito.common.security;

import com.gs.monolito.stock.config.StockSecurityConfig;
import com.gs.monolito.stock.controller.StockController;
import com.gs.monolito.stock.service.IStockService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regresión del 403 al cerrar sesión: el navegador YA tiene la cookie XSRF-TOKEN y
 * autentica con la cookie gs_session (JwtCookieAuthenticationFilter, que deja la
 * autenticación en el SecurityContext sin pasar por ningún repositorio). Spring
 * toma eso como "se autenticó en este request" y su CsrfAuthenticationStrategy
 * BORRABA la cookie XSRF-TOKEN en cada GET que ya la traía — y la regeneraba en el
 * siguiente. Con el dashboard haciendo muchos GET, el navegador quedaba sin cookie
 * y el POST de logout (o cualquier escritura) salía sin X-XSRF-TOKEN: 403.
 *
 * CsrfCookieWebTest no lo veía porque usa @WithMockUser: su request no trae cookie
 * ni pasa por el filtro JWT. Este test sí.
 */
@WebMvcTest(controllers = StockController.class)
@Import({StockSecurityConfig.class, CsrfCookieNoSeBorraWebTest.BeansJwtAdmin.class})
class CsrfCookieNoSeBorraWebTest {

    @TestConfiguration
    static class BeansJwtAdmin {
        @MockBean JwtDecoder jwtDecoder;

        @Bean
        JwtAuthenticationConverter jwtAuthenticationConverter() {
            JwtAuthenticationConverter conv = new JwtAuthenticationConverter();
            conv.setJwtGrantedAuthoritiesConverter(jwt -> List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
            return conv;
        }

        @Bean
        JwtCookieAuthenticationFilter jwtCookieAuthenticationFilter(JwtDecoder decoder, JwtAuthenticationConverter conv) {
            return new JwtCookieAuthenticationFilter(decoder, conv);
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private JwtDecoder jwtDecoder;
    @MockBean private IStockService stockService;

    @BeforeEach
    void sesionDeAdmin() {
        Instant ahora = Instant.now();
        when(jwtDecoder.decode("token-admin")).thenReturn(
            Jwt.withTokenValue("token-admin").header("alg", "none").subject("admin")
               .issuedAt(ahora).expiresAt(ahora.plusSeconds(1800)).build());
    }

    private MvcResult getComoElNavegador(String xsrfQueYaTiene) throws Exception {
        return mvc.perform(get("/api/stock")
                .cookie(new Cookie(JwtCookieAuthenticationFilter.COOKIE_NAME, "token-admin"),
                        new Cookie("XSRF-TOKEN", xsrfQueYaTiene)))
            .andExpect(status().isOk())
            .andReturn();
    }

    @Test
    void unGetQueYaTraeLaCookieXsrfNoLaBorra() throws Exception {
        Cookie enLaRespuesta = getComoElNavegador("token-que-ya-tenia").getResponse().getCookie("XSRF-TOKEN");

        // Lo correcto: no tocarla (sin Set-Cookie) o reenviarla con valor. Lo que
        // rompía todo: un Set-Cookie XSRF-TOKEN con Max-Age=0 (borrarla).
        if (enLaRespuesta != null) {
            assertThat(enLaRespuesta.getMaxAge()).as("Max-Age de la cookie XSRF-TOKEN en la respuesta").isNotZero();
            assertThat(enLaRespuesta.getValue()).as("valor de la cookie XSRF-TOKEN").isNotEmpty();
        }
    }

    @Test
    void variosGetsSeguidosNuncaDejanAlNavegadorSinLaCookie() throws Exception {
        // Simula el jar del navegador: aplica cada Set-Cookie (borrar o reemplazar).
        String jar = "token-inicial";
        for (int i = 0; i < 6; i++) {
            Cookie c = getComoElNavegador(jar == null ? "" : jar).getResponse().getCookie("XSRF-TOKEN");
            if (c != null) {
                jar = c.getMaxAge() == 0 || c.getValue().isEmpty() ? null : c.getValue();
            }
            assertThat(jar).as("el navegador se quedó sin XSRF-TOKEN tras el GET nº " + (i + 1)).isNotNull();
        }
    }
}
