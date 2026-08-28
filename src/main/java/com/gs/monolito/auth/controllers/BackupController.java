package com.gs.monolito.auth.controllers;

import com.gs.monolito.auth.service.AuditoriaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Dispara el backup de la base de datos a demanda (el botón "Hacer backup ahora"
 * de la UI). El contenedor de backup sigue siendo un proceso separado (Etapa 7):
 * este endpoint solo reenvía la orden a su mini-servidor de disparo con una key
 * interna que nunca viaja al navegador.
 */
@Tag(name = "Backup", description = "Disparo manual del backup de la base de datos (solo ADMIN)")
@RestController
@RequestMapping("/api/auth/backup")
public class BackupController {

    private final AuditoriaService auditoriaService;
    private final RestClient restClient;
    private final String backupKey;

    public BackupController(AuditoriaService auditoriaService,
                            @Value("${BACKUP_URI:http://localhost:3002}") String backupUri,
                            @Value("${GS_INTERNAL_API_KEY:}") String backupKey) {
        this.auditoriaService = auditoriaService;
        this.restClient = RestClient.builder().baseUrl(backupUri).build();
        this.backupKey = backupKey;
    }

    @Operation(summary = "Ejecutar un backup ahora",
               description = "Lanza el backup completo (dumps de las 5 bases + archivos de MinIO → Google Drive) " +
                             "en segundo plano. Requiere rol ADMIN.")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Backup iniciado en segundo plano"),
        @ApiResponse(responseCode = "409", description = "Ya hay un backup en curso"),
        @ApiResponse(responseCode = "503", description = "El servicio de backup no respondió"),
        @ApiResponse(responseCode = "403", description = "Sin permisos de administrador")
    })
    @PostMapping("/run")
    public ResponseEntity<?> ejecutar(@AuthenticationPrincipal Jwt jwt) {
        try {
            restClient.post()
                    .uri("/run")
                    .header("X-Backup-Key", backupKey)
                    .retrieve()
                    .toBodilessEntity();

            auditoriaService.registrar(jwt != null ? jwt.getSubject() : "sistema",
                    "BACKUP", "Backup manual disparado", "Backup",
                    "Ejecutado a demanda desde el panel");

            return ResponseEntity.accepted().body(Map.of(
                    "ok", true,
                    "mensaje", "Backup iniciado. Puede tardar unos minutos; el resultado queda en el log del backup."));
        } catch (org.springframework.web.client.HttpClientErrorException.Conflict e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Ya hay un backup en curso. Esperá a que termine."));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "No se pudo contactar al servicio de backup: " + e.getMessage()));
        }
    }
}
