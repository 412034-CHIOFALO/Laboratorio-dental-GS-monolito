package com.gs.monolito.finanzas.controller;

import com.gs.monolito.finanzas.dto.ReporteMensualResponse;
import com.gs.monolito.finanzas.exception.BusinessException;
import com.gs.monolito.finanzas.service.FinanzasComprobanteStorageService;
import com.gs.monolito.finanzas.service.ReporteMensualService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.util.List;

/**
 * Reportes financieros mensuales archivados. Se generan automáticamente el día 1 de cada mes
 * (mes anterior) y también se pueden generar manualmente. Acceso restringido a ADMIN.
 */
@Tag(name = "Reportes mensuales", description = "Reportes financieros mensuales archivados en el sistema")
@RestController
@RequestMapping("/api/finanzas/reportes")
@RequiredArgsConstructor
public class ReporteMensualController {

    private final ReporteMensualService service;
    private final FinanzasComprobanteStorageService minioStorage;

    @Operation(summary = "Lista los reportes mensuales archivados",
               description = "Devuelve los reportes ordenados del más reciente al más antiguo.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado obtenido"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado — se requiere rol ADMIN")
    })
    @GetMapping
    public ResponseEntity<List<ReporteMensualResponse>> listar() {
        return ResponseEntity.ok(service.listar());
    }

    @Operation(summary = "Genera (o regenera) el reporte de un mes",
               description = "Produce el PDF del mes indicado y lo archiva. Si ya existía, lo sobrescribe.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Reporte generado y archivado"),
        @ApiResponse(responseCode = "422", description = "Mes inválido o almacenamiento no disponible"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado — se requiere rol ADMIN")
    })
    @PostMapping("/generar")
    public ResponseEntity<ReporteMensualResponse> generar(
            @Parameter(description = "Año (ej: 2026)", required = true) @RequestParam int anio,
            @Parameter(description = "Mes (1-12)", required = true) @RequestParam int mes) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.generar(anio, mes, false));
    }

    @Operation(summary = "Descarga el PDF de un reporte archivado",
               description = "Sirve el PDF en streaming a través del propio backend (proxy), sin exponer MinIO directamente al navegador.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "PDF servido correctamente"),
        @ApiResponse(responseCode = "422", description = "Reporte inexistente"),
        @ApiResponse(responseCode = "503", description = "MinIO no disponible — no se pudo obtener el archivo"),
        @ApiResponse(responseCode = "403", description = "Acceso denegado — se requiere rol ADMIN")
    })
    @GetMapping("/{id}/archivo")
    public ResponseEntity<InputStreamResource> archivo(
            @Parameter(description = "ID del reporte archivado", required = true) @PathVariable Long id) {
        String objectKey = service.objectKey(id);
        InputStream in = minioStorage.descargar(objectKey);
        if (in == null) {
            throw new BusinessException("No se pudo obtener el reporte (MinIO no disponible)");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(new InputStreamResource(in));
    }
}
