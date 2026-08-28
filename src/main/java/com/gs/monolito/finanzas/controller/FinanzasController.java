package com.gs.monolito.finanzas.controller;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;

import com.gs.monolito.finanzas.dto.ComprobanteRequest;
import com.gs.monolito.finanzas.dto.ComprobanteResponse;
import com.gs.monolito.finanzas.dto.CuentaCorrienteOdontologoResponse;
import com.gs.monolito.finanzas.dto.PagoCuentaCorrienteRequest;
import com.gs.monolito.finanzas.dto.PagoCuentaCorrienteResponse;
import com.gs.monolito.finanzas.service.IFinanzasService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * Endpoints para la gestión de comprobantes de deuda emitidos a odontólogos
 * y el seguimiento de sus cuentas corrientes.
 */
@Tag(name = "Comprobantes y Cuentas Corrientes", description = "Emisión y seguimiento de comprobantes de deuda, y ranking de morosos")
@RestController
@RequestMapping("/api/finanzas")
@RequiredArgsConstructor
@Validated
public class FinanzasController {

    private final IFinanzasService service;

    @Operation(summary = "Lista todos los comprobantes emitidos",
               description = "Devuelve todos los comprobantes sin importar su estado (PENDIENTE, COBRADO, VENCIDO).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado obtenido correctamente"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado")
    })
    @GetMapping("/comprobantes")
    public ResponseEntity<List<ComprobanteResponse>> listarTodos() {
        return ResponseEntity.ok(service.listarTodos());
    }

    @Operation(summary = "Lista comprobantes pendientes de cobro",
               description = "Filtra únicamente los comprobantes en estado PENDIENTE (sin cobrar).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado de pendientes obtenido"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado")
    })
    @GetMapping("/comprobantes/pendientes")
    public ResponseEntity<List<ComprobanteResponse>> listarPendientes() {
        return ResponseEntity.ok(service.listarPendientes());
    }

    @Operation(summary = "Comprobantes de un odontólogo",
               description = "Devuelve todos los comprobantes emitidos a un odontólogo puntual, identificado por su ID de auth.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Comprobantes del odontólogo obtenidos"),
        @ApiResponse(responseCode = "404", description = "Odontólogo no encontrado"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado")
    })
    @GetMapping("/comprobantes/odontologo/{odontologoId}")
    public ResponseEntity<List<ComprobanteResponse>> listarPorOdontologo(
            @Parameter(description = "ID del odontólogo en el módulo auth", required = true)
            @PathVariable @Positive Long odontologoId) {
        return ResponseEntity.ok(service.listarPorOdontologo(odontologoId));
    }

    @Operation(summary = "Saldo deudor total de un odontólogo",
               description = "Retorna el monto total de comprobantes PENDIENTES del odontólogo indicado.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Saldo calculado correctamente"),
        @ApiResponse(responseCode = "404", description = "Odontólogo no encontrado"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado")
    })
    @GetMapping("/saldo/odontologo/{odontologoId}")
    public ResponseEntity<BigDecimal> saldoPendiente(
            @Parameter(description = "ID del odontólogo en el módulo auth", required = true)
            @PathVariable @Positive Long odontologoId) {
        return ResponseEntity.ok(service.saldoPendienteOdontologo(odontologoId));
    }

    @Operation(summary = "Busca un comprobante por ID",
               description = "Devuelve el detalle completo de un comprobante dado su identificador interno.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Comprobante encontrado"),
        @ApiResponse(responseCode = "404", description = "Comprobante no encontrado"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado")
    })
    @GetMapping("/comprobantes/{id}")
    public ResponseEntity<ComprobanteResponse> buscarPorId(
            @Parameter(description = "ID interno del comprobante", required = true)
            @PathVariable @Positive Long id) {
        return ResponseEntity.ok(service.buscarPorId(id));
    }

    @Operation(summary = "Emite un nuevo comprobante de deuda",
               description = "Crea un comprobante asociado a un pedido entregado al odontólogo. El estado inicial es PENDIENTE.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Comprobante emitido correctamente"),
        @ApiResponse(responseCode = "400", description = "Datos del request inválidos"),
        @ApiResponse(responseCode = "409", description = "Ya existe un comprobante para ese pedido"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado")
    })
    @PostMapping("/comprobantes")
    public ResponseEntity<ComprobanteResponse> emitir(
            @Valid @RequestBody ComprobanteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.emitir(request));
    }

    @Operation(summary = "Sincroniza el monto del comprobante de un pedido",
               description = "Llamado por el módulo pedidos cuando se corrige el precio de un pedido ya entregado. " +
                             "Si el pedido todavía no tiene comprobante, no hace nada.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Monto sincronizado (o nada que sincronizar)"),
        @ApiResponse(responseCode = "422", description = "El nuevo monto es menor a lo ya cobrado"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado")
    })
    @PatchMapping("/comprobantes/pedido/{pedidoId}/monto")
    public ResponseEntity<Void> actualizarMontoPorPedido(
            @Parameter(description = "ID del pedido", required = true)
            @PathVariable @Positive Long pedidoId,
            @Valid @RequestBody ActualizarMontoRequest request) {
        service.actualizarMontoPorPedido(pedidoId, request.monto());
        return ResponseEntity.noContent().build();
    }

    public record ActualizarMontoRequest(
        @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive
        java.math.BigDecimal monto
    ) {}

    @Operation(summary = "Marca un comprobante como cobrado",
               description = "Actualiza el estado del comprobante de PENDIENTE a COBRADO y registra la fecha de cobro.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Comprobante marcado como cobrado"),
        @ApiResponse(responseCode = "404", description = "Comprobante no encontrado"),
        @ApiResponse(responseCode = "409", description = "El comprobante ya estaba cobrado"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado — se requiere rol ADMIN")
    })
    @PatchMapping("/comprobantes/{id}/cobrar")
    public ResponseEntity<ComprobanteResponse> registrarCobro(
            @Parameter(description = "ID interno del comprobante a cobrar", required = true)
            @PathVariable @Positive Long id) {
        return ResponseEntity.ok(service.registrarCobro(id));
    }

    @Operation(summary = "Ranking de odontólogos morosos",
               description = "Retorna una fila por odontólogo con deuda pendiente, ordenados de mayor a menor deuda.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Ranking obtenido correctamente"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado")
    })
    @GetMapping("/cuentas-corrientes")
    public ResponseEntity<List<CuentaCorrienteOdontologoResponse>> rankingMorosos(
            @Parameter(description = "Si es true, incluye también a los odontólogos sin deuda pendiente (ya saldada).")
            @RequestParam(name = "todos", required = false, defaultValue = "false") boolean todos) {
        return ResponseEntity.ok(todos ? service.listarTodasCuentas() : service.rankingMorosos());
    }

    @Operation(summary = "Registrar pago a cuenta corriente",
               description = "Imputa un pago manual a las deudas pendientes del odontólogo (más viejas primero, " +
                             "permitiendo pagos parciales) y registra el ingreso de caja según el medio.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Pago registrado e imputado"),
        @ApiResponse(responseCode = "400", description = "El odontólogo no tiene deudas pendientes"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado")
    })
    @PostMapping("/odontologos/{odontologoId}/pagos")
    public ResponseEntity<PagoCuentaCorrienteResponse> registrarPagoCuentaCorriente(
            @Parameter(description = "ID del odontólogo", required = true)
            @PathVariable @Positive Long odontologoId,
            @Valid @RequestBody PagoCuentaCorrienteRequest request) {
        return ResponseEntity.ok(service.registrarPagoCuentaCorriente(odontologoId, request));
    }

    @Operation(summary = "Histórico de pagos de un odontólogo",
               description = "Lista los pagos a cuenta corriente registrados para el odontólogo, más recientes primero.")
    @ApiResponse(responseCode = "200", description = "Histórico obtenido")
    @GetMapping("/odontologos/{odontologoId}/pagos")
    public ResponseEntity<List<PagoCuentaCorrienteResponse>> historialPagos(
            @Parameter(description = "ID del odontólogo", required = true)
            @PathVariable @Positive Long odontologoId) {
        return ResponseEntity.ok(service.historialPagosOdontologo(odontologoId));
    }
}
