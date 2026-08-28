package com.gs.monolito.finanzas.controller;

import com.gs.monolito.finanzas.dto.CajaMovimientoRequest;
import com.gs.monolito.finanzas.dto.CajaMovimientoResponse;
import com.gs.monolito.finanzas.dto.ResumenCajasResponse;
import com.gs.monolito.finanzas.model.TipoCaja;
import com.gs.monolito.finanzas.service.ICajaService;
import com.gs.monolito.finanzas.service.ReporteCajaPdfService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Endpoints de gestión de las tres cajas del laboratorio dental (Física, Bancaria y Compensación).
 */
@Tag(name = "Cajas", description = "Gestión de las tres cajas del laboratorio (Física, Bancaria y Compensación) con sus movimientos y saldo en tiempo real")
@RestController
@RequestMapping("/api/finanzas/cajas")
@RequiredArgsConstructor
public class CajaController {

    private final ICajaService service;
    private final ReporteCajaPdfService pdfService;

    @Operation(summary = "Resumen consolidado de las tres cajas",
               description = "Devuelve saldos actuales de las tres cajas, total de deuda a proveedores, sueldos pendientes y alertas relevantes.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Resumen obtenido correctamente"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado — se requiere rol ADMIN")
    })
    @GetMapping("/resumen")
    public ResponseEntity<ResumenCajasResponse> resumen() {
        return ResponseEntity.ok(service.obtenerResumen());
    }

    @Operation(summary = "Movimientos de una caja específica",
               description = "Lista todos los movimientos de la caja indicada (FISICA, BANCARIA o COMPENSACION), ordenados por fecha descendente.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado de movimientos obtenido"),
        @ApiResponse(responseCode = "400", description = "Tipo de caja inválido"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado — se requiere rol ADMIN")
    })
    @GetMapping("/{tipoCaja}/movimientos")
    public ResponseEntity<List<CajaMovimientoResponse>> movimientosByCaja(
            @Parameter(description = "Caja a consultar: FISICA, BANCARIA o COMPENSACION", required = true)
            @PathVariable TipoCaja tipoCaja) {
        return ResponseEntity.ok(service.listarMovimientosByCaja(tipoCaja));
    }

    @Operation(summary = "Movimientos de todas las cajas en un rango de fechas",
               description = "Filtra los movimientos de las tres cajas entre las fechas indicadas (ambas inclusive). Formato de fecha: YYYY-MM-DD.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Movimientos del período obtenidos"),
        @ApiResponse(responseCode = "400", description = "Parámetros de fecha inválidos"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado — se requiere rol ADMIN")
    })
    @GetMapping("/movimientos")
    public ResponseEntity<List<CajaMovimientoResponse>> movimientosPeriodo(
            @Parameter(description = "Fecha de inicio del período (YYYY-MM-DD)", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @Parameter(description = "Fecha de fin del período (YYYY-MM-DD)", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return ResponseEntity.ok(service.listarMovimientosByPeriodo(desde, hasta));
    }

    @Operation(summary = "Registra un movimiento manual en una caja",
               description = "Crea un movimiento de ingreso o egreso en la caja indicada. El usuario autenticado queda registrado como creador del movimiento.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Movimiento registrado correctamente"),
        @ApiResponse(responseCode = "400", description = "Datos del request inválidos"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado — se requiere rol ADMIN")
    })
    @PostMapping("/movimiento")
    public ResponseEntity<CajaMovimientoResponse> registrarMovimiento(
            @Valid @RequestBody CajaMovimientoRequest req,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        String usuario = (jwt != null) ? jwt.getClaimAsString("sub") : "sistema";
        return ResponseEntity.status(HttpStatus.CREATED).body(service.registrarMovimiento(req, usuario));
    }

    @Operation(summary = "PDF del cierre diario de caja",
               description = "Genera un PDF con los movimientos del día indicado por caja, subtotales y saldos actuales. Si no se pasa fecha, usa el día de hoy.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "PDF generado (application/pdf)"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado — se requiere rol ADMIN")
    })
    @GetMapping("/reporte/cierre-diario")
    public ResponseEntity<byte[]> cierreDiarioPdf(
            @Parameter(description = "Día a reportar (YYYY-MM-DD). Por defecto, hoy.")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {
        LocalDate dia = (fecha != null) ? fecha : LocalDate.now();
        return pdfResponse(pdfService.cierreDiario(dia), "cierre-diario-" + dia + ".pdf");
    }

    @Operation(summary = "PDF del resumen mensual de caja",
               description = "Genera un PDF con los totales de ingresos/egresos por caja y el detalle de movimientos del mes indicado.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "PDF generado (application/pdf)"),
        @ApiResponse(responseCode = "400", description = "Año/mes inválidos"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado — se requiere rol ADMIN")
    })
    @GetMapping("/reporte/mensual")
    public ResponseEntity<byte[]> resumenMensualPdf(
            @Parameter(description = "Año (ej: 2026)", required = true) @RequestParam int anio,
            @Parameter(description = "Mes (1-12)", required = true) @RequestParam int mes) {
        if (mes < 1 || mes > 12) {
            throw new com.gs.monolito.finanzas.exception.BusinessException("El mes debe estar entre 1 y 12.");
        }
        return pdfResponse(pdfService.resumenMensual(anio, mes),
            "resumen-" + anio + "-" + String.format("%02d", mes) + ".pdf");
    }

    private ResponseEntity<byte[]> pdfResponse(byte[] pdf, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"");
        headers.setContentLength(pdf.length);
        return new ResponseEntity<>(pdf, headers, HttpStatus.OK);
    }
}
