package com.gs.monolito.common.security;

import com.gs.monolito.stock.config.StockSecurityConfig;
import com.gs.monolito.stock.controller.StockController;
import com.gs.monolito.stock.service.IStockService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El frontend (Angular withXsrfConfiguration) lee la cookie XSRF-TOKEN y la
 * reenvía como X-XSRF-TOKEN en cada POST/PUT/PATCH/DELETE. Si el backend no la
 * entrega en los GET, esos requests (incluido el logout) devuelven 403 en
 * producción. Esta prueba pasa por la SecurityFilterChain real (la misma receta
 * está en las 5 chains de dominio) y falla si la cookie no sale.
 */
@WebMvcTest(controllers = StockController.class)
@Import({StockSecurityConfig.class, ReglasDeAccesoWebTest.BeansJwt.class})
class CsrfCookieWebTest {

    @Autowired private MockMvc mvc;
    @MockBean private IStockService stockService;

    @Test
    @WithMockUser(roles = "ADMIN")
    void unGetAutenticadoEntregaLaCookieXsrfToken() throws Exception {
        mvc.perform(get("/api/stock"))
           .andExpect(status().isOk())
           .andExpect(cookie().exists("XSRF-TOKEN"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void porHttpsLaCookieEsLegibleDesdeJsYSecure() throws Exception {
        mvc.perform(get("/api/stock").secure(true))
           .andExpect(cookie().exists("XSRF-TOKEN"))
           .andExpect(cookie().httpOnly("XSRF-TOKEN", false))
           .andExpect(cookie().secure("XSRF-TOKEN", true));
    }
}
