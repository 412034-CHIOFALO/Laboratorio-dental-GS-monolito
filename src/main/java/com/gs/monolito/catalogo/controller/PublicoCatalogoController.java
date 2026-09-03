package com.gs.monolito.catalogo.controller;

import com.gs.monolito.catalogo.dto.ConfiguracionCatalogoPublicoResponse;
import com.gs.monolito.catalogo.dto.TipoTrabajoPublicoResponse;
import com.gs.monolito.catalogo.exception.ResourceNotFoundException;
import com.gs.monolito.catalogo.service.ConfiguracionCatalogoPublicoService;
import com.gs.monolito.catalogo.service.ITipoTrabajoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Catálogo público (sin login) — controlado por el toggle que administra
 * {@link com.gs.monolito.catalogo.controller.ConfiguracionCatalogoPublicoController}.
 * Nunca expone la receta de materiales (ver {@link TipoTrabajoPublicoResponse}).
 */
@Tag(name = "Catálogo público", description = "Lista de trabajos con precio, sin receta de materiales — visible sin login solo si el ADMIN lo habilitó.")
@RestController
@RequestMapping("/api/publico/catalogo")
@RequiredArgsConstructor
public class PublicoCatalogoController {

    private final ITipoTrabajoService service;
    private final ConfiguracionCatalogoPublicoService configuracion;

    @Operation(summary = "¿Está habilitado el catálogo público?",
        description = "Siempre 200 — no expone ningún trabajo, solo el estado del toggle. "
            + "Pensado para que la landing decida si mostrar el link 'Catálogo' sin pedir la lista entera.")
    @GetMapping("/habilitado")
    public ResponseEntity<ConfiguracionCatalogoPublicoResponse> habilitado() {
        return ResponseEntity.ok(new ConfiguracionCatalogoPublicoResponse(configuracion.estaHabilitado()));
    }

    @Operation(summary = "Listar catálogo público",
        description = "Devuelve los trabajos activos con precio, sin receta de materiales. "
            + "404 si el ADMIN no habilitó el catálogo público.")
    @GetMapping
    public ResponseEntity<List<TipoTrabajoPublicoResponse>> listar() {
        if (!configuracion.estaHabilitado()) {
            throw new ResourceNotFoundException("El catálogo público no está habilitado");
        }
        return ResponseEntity.ok(service.listarPublico());
    }
}
