package com.gs.monolito.common.security;

import com.gs.monolito.pedidos.config.PedidosSecurityConfig;
import com.gs.monolito.pedidos.controller.PedidoController;
import com.gs.monolito.pedidos.service.IPedidoService;
import com.gs.monolito.stock.config.StockSecurityConfig;
import com.gs.monolito.stock.controller.StockController;
import com.gs.monolito.stock.service.IStockService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pasa por las SecurityFilterChain reales de stock y pedidos con requests HTTP
 * (los tests unitarios de los controladores no ejercitan estas reglas).
 */
@WebMvcTest(controllers = {StockController.class, PedidoController.class})
@Import({StockSecurityConfig.class, PedidosSecurityConfig.class, ReglasDeAccesoWebTest.BeansJwt.class})
class ReglasDeAccesoWebTest {

    @TestConfiguration
    static class BeansJwt {
        @MockBean JwtDecoder jwtDecoder;

        @Bean
        JwtAuthenticationConverter jwtAuthenticationConverter() {
            return new JwtAuthenticationConverter();
        }

        @Bean
        JwtCookieAuthenticationFilter jwtCookieAuthenticationFilter(JwtDecoder decoder, JwtAuthenticationConverter conv) {
            return new JwtCookieAuthenticationFilter(decoder, conv);
        }
    }

    @Autowired private MockMvc mvc;
    @MockBean private IStockService stockService;
    @MockBean private IPedidoService pedidoService;

    private static final String MATERIAL = """
        {"nombre": "Yeso tipo IV", "categoria": "YESO", "stockActual": 10,
         "stockMinimo": 2, "unidadMedida": "kg"}
        """;

    // ── Stock: PUT tenía regla faltante (cualquier rol logueado podía editar) ──

    @Test
    @WithMockUser(roles = "TECNICO")
    void tecnico_noPuedeEditarUnMaterial() throws Exception {
        mvc.perform(put("/api/stock/1").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(MATERIAL))
           .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ODONTOLOGO")
    void odontologo_noPuedeEditarUnMaterial() throws Exception {
        mvc.perform(put("/api/stock/1").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(MATERIAL))
           .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_siPuedeEditarUnMaterial() throws Exception {
        mvc.perform(put("/api/stock/1").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(MATERIAL))
           .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void metodoSinReglaExplicita_seRechazaAunqueSeaAdmin() throws Exception {
        // Fail-closed: antes caía en anyRequest().authenticated().
        mvc.perform(patch("/api/stock/1").with(csrf()))
           .andExpect(status().isForbidden());
    }

    // ── Pedidos: ODONTOLOGO sin escritura hasta que exista su portal ──────────

    @Test
    @WithMockUser(roles = "ODONTOLOGO")
    void odontologo_noPuedeCrearPedidos() throws Exception {
        mvc.perform(post("/api/pedidos").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
           .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ODONTOLOGO")
    void odontologo_noPuedeSubirDocumentosAPedidos() throws Exception {
        mvc.perform(post("/api/pedidos/1/docs").with(csrf()))
           .andExpect(status().isForbidden());
    }

    @Test
    void sinSesion_devuelve401() throws Exception {
        mvc.perform(put("/api/stock/1").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(MATERIAL))
           .andExpect(status().isUnauthorized());
    }
}
