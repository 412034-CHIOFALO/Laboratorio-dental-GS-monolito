package com.gs.monolito.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuración global de OpenAPI para el monolito — reemplaza a los 5
 * `OpenApiConfig` (uno por microservicio) por uno solo, ya que ahora es una
 * única API con un único /v3/api-docs y /swagger-ui.html.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Monolito API — Laboratorio G&S")
                .version("1.0.0")
                .description("Autenticación, pedidos, catálogo, finanzas y stock del laboratorio, unificados en un solo servicio.")
                .contact(new Contact()
                    .name("Laboratorio G&S")
                    .email("nicolas.mauricio.chiofalo@gmail.com")))
            .addSecurityItem(new SecurityRequirement().addList("BearerAuth"))
            .components(new Components()
                .addSecuritySchemes("BearerAuth", new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Token JWT obtenido del endpoint POST /api/auth/login")));
    }
}
