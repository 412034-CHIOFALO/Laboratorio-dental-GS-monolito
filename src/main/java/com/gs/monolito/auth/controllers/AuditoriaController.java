package com.gs.monolito.auth.controllers;

import com.gs.monolito.auth.dto.AuditoriaEventoResponse;
import com.gs.monolito.auth.model.AuditoriaEvento;
import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.common.dto.PaginaResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador REST para la bitácora de auditoría del sistema.
 * <p>
 * El endpoint de ingesta (POST /auditoria/ingest) que existía acá para que
 * otros microservicios reportaran eventos por HTTP se eliminó al fusionar
 * todo en el monolito: ahora esos módulos llaman directo a
 * {@link AuditoriaService#registrar} en el mismo proceso.
 * </p>
 * <p>
 * El listado es paginado: la bitácora crece para siempre (nunca se borra, ver
 * el pie de página del front) y devolverla entera en un solo request no
 * escala — se vuelve más lenta y más pesada cada mes que pasa.
 * </p>
 */
@Tag(name = "Auditoría", description = "Bitácora inmutable de eventos del sistema (solo administradores)")
@RestController
@RequestMapping("/api/auth")
public class AuditoriaController {

    private static final int TAMANIO_PAGINA_DEFAULT = 50;
    private static final int TAMANIO_PAGINA_MAX = 200;

    private final AuditoriaService auditoriaService;

    public AuditoriaController(AuditoriaService auditoriaService) {
        this.auditoriaService = auditoriaService;
    }

    @Operation(
        summary = "Listar eventos de auditoría (paginado)",
        description = "Devuelve una página de la bitácora de eventos del sistema, la más reciente primero. " +
                      "Acepta filtro por tipo exacto y búsqueda de texto libre sobre usuario/acción/entidad/detalle. " +
                      "Requiere rol ADMIN (Bearer JWT con claim roles=ROLE_ADMIN)."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Página de eventos devuelta correctamente",
            content = @Content(mediaType = "application/json",
                schema = @Schema(example = "{\"content\":[{\"id\":1,\"timestamp\":\"2025-06-17T10:00:00Z\"," +
                        "\"usuario\":\"admin\",\"tipo\":\"LOGIN\",\"accion\":\"Inicio de sesión\"," +
                        "\"entidad\":\"Sesión\",\"detalle\":\"Login exitoso · roles: ROLE_ADMIN\"}]," +
                        "\"page\":0,\"size\":50,\"totalElements\":1,\"totalPages\":1}"))),
        @ApiResponse(responseCode = "403", description = "Sin permisos de administrador", content = @Content)
    })
    @GetMapping("/auditoria")
    public ResponseEntity<PaginaResponse<AuditoriaEventoResponse>> listar(
            @Parameter(description = "Número de página (0-based)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de página (máx. " + TAMANIO_PAGINA_MAX + ")")
            @RequestParam(defaultValue = "" + TAMANIO_PAGINA_DEFAULT) int size,
            @Parameter(description = "Filtra por tipo exacto de evento (LOGIN, CREAR, PAGO, etc.)")
            @RequestParam(required = false) String tipo,
            @Parameter(description = "Búsqueda de texto libre sobre usuario/acción/entidad/detalle")
            @RequestParam(required = false) String q) {
        int sizeSano = Math.min(Math.max(size, 1), TAMANIO_PAGINA_MAX);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), sizeSano, Sort.by(Sort.Direction.DESC, "timestamp"));

        Page<AuditoriaEvento> eventos = auditoriaService.buscar(tipo, q, pageable);
        return ResponseEntity.ok(PaginaResponse.from(eventos, AuditoriaEventoResponse::from));
    }
}
