package com.gs.monolito.pedidos.controller;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;

import com.gs.monolito.pedidos.dto.OdontologoRequest;
import com.gs.monolito.pedidos.dto.OdontologoResponse;
import com.gs.monolito.pedidos.service.IOdontologoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controlador REST para la gestión de odontólogos clientes del laboratorio dental.
 * <p>
 * Los odontólogos son los profesionales que encargan trabajos al laboratorio. Se pueden
 * crear automáticamente durante la carga de un pedido ("find or create" por nombre) o
 * de forma manual desde el ABM de clientes.
 * </p>
 */
@Tag(name = "Odontólogos", description = "ABM de odontólogos clientes del laboratorio. Permite buscar por nombre (autocomplete), registrar nuevos profesionales y desactivarlos con soft-delete para preservar el historial de pedidos.")
@RestController
@RequestMapping("/api/odontologos")
@RequiredArgsConstructor
@Validated
public class OdontologoController {

    private final IOdontologoService service;

    @Operation(
        summary = "Listar odontólogos activos / buscar por nombre",
        description = "Sin parámetros devuelve todos los odontólogos activos ordenados alfabéticamente. Con el parámetro 'q' filtra por fragmento de nombre (útil para autocomplete en el formulario de nuevo pedido)."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Lista de odontólogos obtenida correctamente")
    })
    @GetMapping
    public ResponseEntity<List<OdontologoResponse>> listar(
            @Parameter(description = "Fragmento de nombre para filtrar (autocomplete). Ej: 'garcia'", example = "garcia")
            @RequestParam(name = "q", required = false) String query,
            @Parameter(description = "Si es true, incluye también los odontólogos desactivados. Solo para el panel de gestión — no usar en selectores de 'Nuevo pedido'.")
            @RequestParam(name = "incluirInactivos", required = false, defaultValue = "false") boolean incluirInactivos) {
        if (query != null && !query.isBlank()) {
            return ResponseEntity.ok(service.buscarPorNombre(query));
        }
        return ResponseEntity.ok(incluirInactivos ? service.listarTodos() : service.listarActivos());
    }

    @Operation(
        summary = "Buscar odontólogo por ID",
        description = "Devuelve el detalle completo de un odontólogo: nombre, DNI, CUIT, matrícula, clínica, dirección y datos de contacto."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Odontólogo encontrado"),
        @ApiResponse(responseCode = "404", description = "Odontólogo no encontrado con el ID proporcionado")
    })
    @GetMapping("/{id}")
    public ResponseEntity<OdontologoResponse> buscarPorId(
            @Parameter(description = "ID interno del odontólogo", example = "5")
            @PathVariable @Positive Long id) {
        return ResponseEntity.ok(service.buscarPorId(id));
    }

    @Operation(
        summary = "Registrar nuevo odontólogo",
        description = "Crea un nuevo odontólogo en el sistema. DNI y CUIT son únicos cuando están presentes. El odontólogo queda activo por defecto."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Odontólogo creado exitosamente"),
        @ApiResponse(responseCode = "400", description = "Datos de entrada inválidos o incompletos"),
        @ApiResponse(responseCode = "409", description = "Ya existe un odontólogo con ese DNI o CUIT")
    })
    @PostMapping
    public ResponseEntity<OdontologoResponse> crear(
            @Parameter(description = "Datos del nuevo odontólogo")
            @Valid @RequestBody OdontologoRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.crear(request));
    }

    @Operation(
        summary = "Actualizar datos de un odontólogo",
        description = "Modifica los datos editables de un odontólogo existente: nombre, DNI, CUIT, teléfono, email, matrícula, clínica y dirección."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Odontólogo actualizado correctamente"),
        @ApiResponse(responseCode = "400", description = "Datos de entrada inválidos"),
        @ApiResponse(responseCode = "404", description = "Odontólogo no encontrado"),
        @ApiResponse(responseCode = "409", description = "DNI o CUIT duplicado con otro registro")
    })
    @PutMapping("/{id}")
    public ResponseEntity<OdontologoResponse> actualizar(
            @Parameter(description = "ID del odontólogo a actualizar", example = "5")
            @PathVariable @Positive Long id,
            @Parameter(description = "Nuevos datos del odontólogo")
            @Valid @RequestBody OdontologoRequest request) {
        return ResponseEntity.ok(service.actualizar(id, request));
    }

    @Operation(
        summary = "Desactivar odontólogo (soft delete)",
        description = "Marca el odontólogo como inactivo. No se elimina físicamente para preservar las referencias en el historial de pedidos. Solo disponible para el rol ADMIN."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Odontólogo desactivado correctamente"),
        @ApiResponse(responseCode = "404", description = "Odontólogo no encontrado")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> desactivar(
            @Parameter(description = "ID del odontólogo a desactivar", example = "5")
            @PathVariable @Positive Long id) {
        service.desactivar(id);
        return ResponseEntity.noContent().build();
    }
}
