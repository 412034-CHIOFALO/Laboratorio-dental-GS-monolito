package com.gs.monolito.catalogo.controller;

import com.gs.monolito.catalogo.dto.ConfiguracionCatalogoPublicoRequest;
import com.gs.monolito.catalogo.dto.ConfiguracionCatalogoPublicoResponse;
import com.gs.monolito.catalogo.service.ConfiguracionCatalogoPublicoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN-only: prende/apaga el catálogo público (ver {@link PublicoCatalogoController}).
 * Vive bajo /api/catalogo (no /api/publico) a propósito — es la pantalla de
 * Configuración del dashboard la que llama esto, nunca un visitante anónimo.
 */
@Tag(name = "Catálogo público", description = "Prender/apagar el catálogo público. Requiere ROLE_ADMIN.")
@RestController
@RequestMapping("/api/catalogo/configuracion-publica")
@RequiredArgsConstructor
@Validated
public class ConfiguracionCatalogoPublicoController {

    private final ConfiguracionCatalogoPublicoService service;

    @Operation(summary = "Ver si el catálogo público está habilitado", description = "Requiere ROLE_ADMIN.")
    @GetMapping
    public ResponseEntity<ConfiguracionCatalogoPublicoResponse> obtener() {
        return ResponseEntity.ok(service.obtener());
    }

    @Operation(summary = "Prender/apagar el catálogo público", description = "Requiere ROLE_ADMIN.")
    @PutMapping
    public ResponseEntity<ConfiguracionCatalogoPublicoResponse> actualizar(@Valid @RequestBody ConfiguracionCatalogoPublicoRequest request) {
        return ResponseEntity.ok(service.actualizar(request.habilitado()));
    }
}
