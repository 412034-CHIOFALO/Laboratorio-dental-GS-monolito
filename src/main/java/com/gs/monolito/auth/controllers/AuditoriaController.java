package com.gs.monolito.auth.controllers;

import com.gs.monolito.auth.service.AuditoriaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Controlador REST para la bitácora de auditoría del sistema.
 * <p>
 * El endpoint de ingesta (POST /auditoria/ingest) que existía acá para que
 * otros microservicios reportaran eventos por HTTP se eliminó al fusionar
 * todo en el monolito: ahora esos módulos llaman directo a
 * {@link AuditoriaService#registrar} en el mismo proceso.
 * </p>
 */
@Tag(name = "Auditoría", description = "Bitácora inmutable de eventos del sistema (solo administradores)")
@RestController
@RequestMapping("/api/auth")
public class AuditoriaController {

    private final AuditoriaService auditoriaService;

    public AuditoriaController(AuditoriaService auditoriaService) {
        this.auditoriaService = auditoriaService;
    }

    @Operation(
        summary = "Listar todos los eventos de auditoría",
        description = "Devuelve la bitácora completa de eventos del sistema en orden cronológico descendente. " +
                      "Incluye logins, creaciones, ediciones de usuarios y cambios de estado. " +
                      "Requiere rol ADMIN (Bearer JWT con claim roles=ROLE_ADMIN)."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Lista de eventos devuelta correctamente",
            content = @Content(mediaType = "application/json",
                schema = @Schema(example = "[{\"id\":1,\"timestamp\":\"2025-06-17T10:00:00Z\"," +
                        "\"usuario\":\"admin\",\"tipo\":\"LOGIN\",\"accion\":\"Inicio de sesión\"," +
                        "\"entidad\":\"Sesión\",\"detalle\":\"Login exitoso · roles: ROLE_ADMIN\"}]"))),
        @ApiResponse(responseCode = "403", description = "Sin permisos de administrador", content = @Content)
    })
    @GetMapping("/auditoria")
    public ResponseEntity<List<Map<String, Object>>> listar() {
        List<Map<String, Object>> eventos = auditoriaService.listarTodos().stream()
            .map(e -> {
                Map<String, Object> m = new HashMap<>();
                m.put("id",        e.getId());
                m.put("timestamp", e.getTimestamp().toString());
                m.put("usuario",   e.getUsuario());
                m.put("tipo",      e.getTipo());
                m.put("accion",    e.getAccion());
                m.put("entidad",   e.getEntidad());
                m.put("detalle",   e.getDetalle() != null ? e.getDetalle() : "");
                return m;
            })
            .toList();
        return ResponseEntity.ok(eventos);
    }
}
