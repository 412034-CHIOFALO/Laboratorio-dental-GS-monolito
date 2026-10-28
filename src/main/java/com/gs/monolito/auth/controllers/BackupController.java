package com.gs.monolito.auth.controllers;

import com.gs.monolito.auth.service.AuditoriaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Backup desde el panel (Configuración → Copias de seguridad): hacerlo ahora, ver las copias que hay y bajarlas a la
 * computadora. El contenedor de backup sigue siendo un proceso separado (Etapa 7): este controlador solo reenvía los
 * pedidos a su mini-servidor con una key interna que nunca viaja al navegador.
 */
@Tag(name = "Backup", description = "Backup de la base de datos y los archivos: disparo manual, estado y descarga (solo ADMIN)")
@RestController
@RequestMapping("/api/auth/backup")
public class BackupController {

    /** Así se llama la carpeta de cada copia (AAAA-MM-DD_HH-MM). Se valida acá y de nuevo en el servicio de backup. */
    private static final Pattern NOMBRE_COPIA = Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}");

    private final AuditoriaService auditoriaService;
    private final RestClient restClient;
    private final HttpClient descargas;
    private final String backupUri;
    private final String backupKey;

    public BackupController(AuditoriaService auditoriaService,
                            @Value("${BACKUP_URI:http://localhost:3002}") String backupUri,
                            @Value("${GS_INTERNAL_API_KEY:}") String backupKey) {
        this.auditoriaService = auditoriaService;
        this.backupUri = backupUri.endsWith("/") ? backupUri.substring(0, backupUri.length() - 1) : backupUri;
        this.restClient = RestClient.builder()
                .baseUrl(this.backupUri)
                // TriggerServer responde 202 apenas lanza el proceso en background
                // (ver backup/TriggerServer.java) — si no responde en unos segundos
                // es que el contenedor de backup no está, no tiene sentido esperar más.
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(Duration.ofSeconds(3))
                        .withReadTimeout(Duration.ofSeconds(5))))
                .build();
        // La descarga puede tardar minutos (los escaneos 3D pesan): se corta si el servicio no contesta, no por lento.
        this.descargas = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        this.backupKey = backupKey;
    }

    @Operation(summary = "Ejecutar un backup ahora",
               description = "Lanza el backup completo (volcado de la base + archivos de MinIO) en segundo plano. " +
                             "Queda una copia en el servidor y, si Google Drive está configurado, también se sube. " +
                             "Requiere rol ADMIN.")
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

            auditoriaService.registrar(usuario(jwt),
                    "BACKUP", "Backup manual disparado", "Backup",
                    "Ejecutado a demanda desde el panel");

            return ResponseEntity.accepted().body(Map.of(
                    "ok", true,
                    "mensaje", "Backup iniciado. Puede tardar unos minutos; la copia aparece en la lista cuando termina."));
        } catch (org.springframework.web.client.HttpClientErrorException.Conflict e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Ya hay un backup en curso. Esperá a que termine."));
        } catch (Exception e) {
            return sinServicio(e);
        }
    }

    @Operation(summary = "Estado de los backups",
               description = "Las copias que hay en el servidor (con su tamaño y si llegaron a Google Drive), si hay " +
                             "un backup en curso y si Google Drive está configurado. Requiere rol ADMIN.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "enCurso, driveConfigurado y copias[] (nombre, bytes, destino)"),
        @ApiResponse(responseCode = "503", description = "El servicio de backup no respondió")
    })
    @GetMapping("/estado")
    public ResponseEntity<?> estado() {
        try {
            Map<String, Object> estado = restClient.get()
                    .uri("/estado")
                    .header("X-Backup-Key", backupKey)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return ResponseEntity.ok(estado);
        } catch (Exception e) {
            return sinServicio(e);
        }
    }

    @Operation(summary = "Descargar una copia",
               description = "Baja la copia completa (base + archivos) como un .tar, en streaming. " +
                             "Deja un registro en la auditoría. Requiere rol ADMIN.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "El .tar de la copia"),
        @ApiResponse(responseCode = "400", description = "Nombre de copia inválido"),
        @ApiResponse(responseCode = "404", description = "No existe esa copia"),
        @ApiResponse(responseCode = "503", description = "El servicio de backup no respondió")
    })
    @GetMapping("/copias/{nombre}/archivo")
    public ResponseEntity<?> descargar(@PathVariable String nombre, @AuthenticationPrincipal Jwt jwt) {
        if (!NOMBRE_COPIA.matcher(nombre).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nombre de copia inválido."));
        }
        HttpResponse<InputStream> respuesta;
        try {
            respuesta = descargas.send(
                    HttpRequest.newBuilder(URI.create(backupUri + "/descargar?copia=" + nombre))
                            .header("X-Backup-Key", backupKey)
                            .timeout(Duration.ofSeconds(10))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            return sinServicio(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return sinServicio(e);
        }

        if (respuesta.statusCode() != 200) {
            cerrar(respuesta.body());
            if (respuesta.statusCode() == 404) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Esa copia ya no existe en el servidor."));
            }
            return sinServicio(new IllegalStateException("el servicio de backup respondió " + respuesta.statusCode()));
        }

        auditoriaService.registrar(usuario(jwt), "BACKUP", "Copia de seguridad descargada", "Backup",
                "Copia " + nombre + " bajada desde el panel");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-tar"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("gs-backup-" + nombre + ".tar").build().toString())
                .body(new InputStreamResource(respuesta.body()));
    }

    private static String usuario(Jwt jwt) {
        return jwt != null ? jwt.getSubject() : "sistema";
    }

    private static ResponseEntity<Map<String, String>> sinServicio(Exception e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "No se pudo contactar al servicio de backup: " + e.getMessage()));
    }

    private static void cerrar(InputStream in) {
        try {
            in.close();
        } catch (IOException ignorado) {
            // ya no hay nada que leer
        }
    }
}
