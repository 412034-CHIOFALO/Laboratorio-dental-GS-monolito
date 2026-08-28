package com.gs.monolito.finanzas.controller;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;

import com.gs.monolito.finanzas.dto.*;
import com.gs.monolito.finanzas.model.TipoCaja;
import com.gs.monolito.finanzas.service.IDeudaProveedorService;
import com.gs.monolito.finanzas.service.IProveedorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Endpoints para el ABM de proveedores de materiales del laboratorio
 * y el registro/consulta de sus deudas pendientes de pago.
 */
@Tag(name = "Proveedores", description = "ABM de proveedores de materiales y registro de deudas")
@RestController
@RequestMapping("/api/finanzas/proveedores")
@RequiredArgsConstructor
@Validated
public class ProveedorController {

    private final IProveedorService provService;
    private final IDeudaProveedorService deudaService;

    @Operation(summary = "Lista todos los proveedores activos",
               description = "Devuelve la lista de proveedores no desactivados (activo = true), con sus datos de contacto.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado obtenido correctamente"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado")
    })
    @GetMapping
    public ResponseEntity<List<ProveedorResponse>> listar() {
        return ResponseEntity.ok(provService.listarActivos());
    }

    @Operation(summary = "Busca un proveedor por ID",
               description = "Devuelve los datos completos de un proveedor dado su identificador interno.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Proveedor encontrado"),
        @ApiResponse(responseCode = "404", description = "Proveedor no encontrado"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado")
    })
    @GetMapping("/{id}")
    public ResponseEntity<ProveedorResponse> buscar(
            @Parameter(description = "ID interno del proveedor", required = true)
            @PathVariable @Positive Long id) {
        return ResponseEntity.ok(provService.buscarPorId(id));
    }

    @Operation(summary = "Crea un nuevo proveedor",
               description = "Registra un nuevo proveedor de materiales con nombre, CUIT, teléfono, email y dirección.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Proveedor creado correctamente"),
        @ApiResponse(responseCode = "400", description = "Datos del request inválidos"),
        @ApiResponse(responseCode = "409", description = "Ya existe un proveedor con ese CUIT"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado")
    })
    @PostMapping
    public ResponseEntity<ProveedorResponse> crear(@Valid @RequestBody ProveedorRequest req) {
        return ResponseEntity.status(201).body(provService.crear(req));
    }

    @Operation(summary = "Actualiza los datos de un proveedor",
               description = "Modifica los datos de contacto y nombre de un proveedor existente.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Proveedor actualizado correctamente"),
        @ApiResponse(responseCode = "400", description = "Datos del request inválidos"),
        @ApiResponse(responseCode = "404", description = "Proveedor no encontrado"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado")
    })
    @PutMapping("/{id}")
    public ResponseEntity<ProveedorResponse> actualizar(
            @Parameter(description = "ID interno del proveedor", required = true)
            @PathVariable @Positive Long id,
            @Valid @RequestBody ProveedorRequest req) {
        return ResponseEntity.ok(provService.actualizar(id, req));
    }

    @Operation(summary = "Desactiva (baja lógica) un proveedor",
               description = "Marca el proveedor como inactivo (activo = false). No elimina el registro para conservar el historial de deudas.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Proveedor desactivado correctamente"),
        @ApiResponse(responseCode = "404", description = "Proveedor no encontrado"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> desactivar(
            @Parameter(description = "ID interno del proveedor", required = true)
            @PathVariable @Positive Long id) {
        provService.desactivar(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Deudas de un proveedor específico",
               description = "Lista todas las deudas (PENDIENTE y PAGADO) asociadas a un proveedor.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Deudas obtenidas correctamente"),
        @ApiResponse(responseCode = "404", description = "Proveedor no encontrado"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado")
    })
    @GetMapping("/{id}/deudas")
    public ResponseEntity<List<DeudaProveedorResponse>> deudas(
            @Parameter(description = "ID interno del proveedor", required = true)
            @PathVariable @Positive Long id) {
        return ResponseEntity.ok(deudaService.listarPorProveedor(id));
    }

    @Operation(summary = "Lista todas las deudas pendientes a proveedores",
               description = "Devuelve las deudas en estado PENDIENTE de todos los proveedores, para gestión de pagos.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Deudas pendientes obtenidas"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado")
    })
    @GetMapping("/deudas/pendientes")
    public ResponseEntity<List<DeudaProveedorResponse>> deudasPendientes() {
        return ResponseEntity.ok(deudaService.listarPendientes());
    }

    @Operation(summary = "Lista todas las deudas a proveedores ya pagadas",
               description = "Devuelve las deudas en estado PAGADO de todos los proveedores, más recientes primero — para completar el historial de pagos manuales en la pantalla de Triangulados.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Deudas pagadas obtenidas"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado")
    })
    @GetMapping("/deudas/pagadas")
    public ResponseEntity<List<DeudaProveedorResponse>> deudasPagadas() {
        return ResponseEntity.ok(deudaService.listarPagadas());
    }

    @Operation(summary = "Registra una deuda con un proveedor",
               description = "Crea un registro de deuda por compra de materiales al proveedor indicado. El estado inicial es PENDIENTE.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Deuda registrada correctamente"),
        @ApiResponse(responseCode = "400", description = "Datos del request inválidos"),
        @ApiResponse(responseCode = "404", description = "Proveedor no encontrado"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado")
    })
    @PostMapping("/deudas")
    public ResponseEntity<DeudaProveedorResponse> registrarDeuda(@Valid @RequestBody DeudaProveedorRequest req) {
        return ResponseEntity.status(201).body(deudaService.registrar(req));
    }

    @Operation(summary = "Marca una deuda como pagada",
               description = "Cambia el estado de la deuda a PAGADO, registra la fecha de pago (hoy) y el egreso "
                   + "correspondiente en la caja indicada.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Deuda marcada como pagada"),
        @ApiResponse(responseCode = "404", description = "Deuda no encontrada"),
        @ApiResponse(responseCode = "422", description = "La deuda ya estaba pagada"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado")
    })
    @PatchMapping("/deudas/{id}/pagar")
    public ResponseEntity<DeudaProveedorResponse> pagarDeuda(
            @Parameter(description = "ID interno de la deuda", required = true)
            @PathVariable @Positive Long id,
            @Parameter(description = "Caja de la que salió el pago (FISICA o BANCARIA)", required = true)
            @RequestParam TipoCaja caja) {
        return ResponseEntity.ok(deudaService.pagar(id, caja));
    }
}
