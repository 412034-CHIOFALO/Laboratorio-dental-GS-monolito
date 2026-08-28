package com.gs.monolito.catalogo.controller;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;

import com.gs.monolito.catalogo.dto.TipoTrabajoRequest;
import com.gs.monolito.catalogo.dto.TipoTrabajoResponse;
import com.gs.monolito.catalogo.model.Categoria;
import com.gs.monolito.catalogo.service.ITipoTrabajoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Controlador REST del catálogo de tipos de trabajo dental.
 */
@Tag(name = "Catálogo de Trabajos", description = "Gestión del catálogo de tipos de trabajo dental: coronas, prótesis, férulas, ortodoncia y demás. Incluye precio de referencia y receta de materiales de stock.")
@RestController
@RequestMapping("/api/catalogo")
@RequiredArgsConstructor
@Validated
public class TipoTrabajoController {

    private final ITipoTrabajoService service;

    @Operation(
        summary = "Listar tipos de trabajo",
        description = "Devuelve todos los tipos de trabajo activos. "
            + "Permite filtrar por categoría (FIJA, REMOVIBLE, ORTODONCIA, ATM, PERSONALIZADO) "
            + "o buscar por nombre (búsqueda parcial, sin distinción de mayúsculas). "
            + "Si se informan ambos parámetros, el filtro por nombre tiene precedencia."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Lista de tipos de trabajo (puede ser vacía)"),
        @ApiResponse(responseCode = "401", description = "Token JWT ausente o inválido"),
        @ApiResponse(responseCode = "403", description = "El usuario no tiene permisos suficientes")
    })
    @GetMapping
    public ResponseEntity<List<TipoTrabajoResponse>> listar(
            @Parameter(description = "Categoría del trabajo dental (FIJA, REMOVIBLE, ORTODONCIA, ATM, PERSONALIZADO)")
            @RequestParam(required = false) Categoria categoria,
            @Parameter(description = "Fragmento del nombre para búsqueda parcial (case-insensitive)")
            @RequestParam(required = false) String nombre) {

        if (nombre != null && !nombre.isBlank()) {
            return ResponseEntity.ok(service.buscarPorNombre(nombre));
        }
        if (categoria != null) {
            return ResponseEntity.ok(service.listarPorCategoria(categoria));
        }
        return ResponseEntity.ok(service.listarActivos());
    }

    @Operation(
        summary = "Obtener tipo de trabajo por ID",
        description = "Retorna el tipo de trabajo con su receta de materiales completa. "
            + "Lanza 404 si el ID no existe o el trabajo fue dado de baja (activo = false)."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Tipo de trabajo encontrado"),
        @ApiResponse(responseCode = "401", description = "Token JWT ausente o inválido"),
        @ApiResponse(responseCode = "404", description = "Tipo de trabajo no encontrado")
    })
    @GetMapping("/{id}")
    public ResponseEntity<TipoTrabajoResponse> buscarPorId(
            @Parameter(description = "ID único del tipo de trabajo", example = "1")
            @PathVariable @Positive Long id) {
        return ResponseEntity.ok(service.buscarPorId(id));
    }

    @Operation(
        summary = "Crear tipo de trabajo",
        description = "Crea un nuevo tipo de trabajo dental con su receta de materiales. "
            + "Requiere ROLE_ADMIN. El nombre debe ser único en la base de datos."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Tipo de trabajo creado exitosamente"),
        @ApiResponse(responseCode = "400", description = "Datos de entrada inválidos (validación fallida)"),
        @ApiResponse(responseCode = "401", description = "Token JWT ausente o inválido"),
        @ApiResponse(responseCode = "403", description = "El usuario no tiene rol ADMIN"),
        @ApiResponse(responseCode = "409", description = "Ya existe un tipo de trabajo con ese nombre")
    })
    @PostMapping
    public ResponseEntity<TipoTrabajoResponse> crear(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Datos del nuevo tipo de trabajo, incluyendo receta de materiales")
            @Valid @RequestBody TipoTrabajoRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.crear(request));
    }

    @Operation(
        summary = "Actualizar tipo de trabajo",
        description = "Reemplaza completamente los datos del tipo de trabajo y su receta de materiales. "
            + "Requiere ROLE_ADMIN. La receta anterior es eliminada y reemplazada por la nueva."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Tipo de trabajo actualizado exitosamente"),
        @ApiResponse(responseCode = "400", description = "Datos de entrada inválidos"),
        @ApiResponse(responseCode = "401", description = "Token JWT ausente o inválido"),
        @ApiResponse(responseCode = "403", description = "El usuario no tiene rol ADMIN"),
        @ApiResponse(responseCode = "404", description = "Tipo de trabajo no encontrado"),
        @ApiResponse(responseCode = "409", description = "Ya existe otro tipo de trabajo con ese nombre")
    })
    @PutMapping("/{id}")
    public ResponseEntity<TipoTrabajoResponse> actualizar(
            @Parameter(description = "ID del tipo de trabajo a actualizar", example = "1")
            @PathVariable @Positive Long id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Datos actualizados del tipo de trabajo")
            @Valid @RequestBody TipoTrabajoRequest request) {
        return ResponseEntity.ok(service.actualizar(id, request));
    }

    @Operation(
        summary = "Dar de baja (soft-delete) un tipo de trabajo",
        description = "Marca el tipo de trabajo como inactivo (activo = false). "
            + "No se elimina físicamente de la base de datos para preservar trazabilidad histórica. "
            + "Requiere ROLE_ADMIN."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Tipo de trabajo dado de baja correctamente"),
        @ApiResponse(responseCode = "401", description = "Token JWT ausente o inválido"),
        @ApiResponse(responseCode = "403", description = "El usuario no tiene rol ADMIN"),
        @ApiResponse(responseCode = "404", description = "Tipo de trabajo no encontrado")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminar(
            @Parameter(description = "ID del tipo de trabajo a dar de baja", example = "1")
            @PathVariable @Positive Long id) {
        service.eliminar(id);
        return ResponseEntity.noContent().build();
    }
}
