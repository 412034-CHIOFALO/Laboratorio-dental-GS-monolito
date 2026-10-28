package com.gs.monolito.auth.controllers;

import com.gs.monolito.auth.service.AuditoriaService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * El panel habla con el servicio de backup a través de este controlador: la clave interna tiene que viajar, el nombre
 * de la copia no puede ser una ruta, y bajar una copia (toda la base) deja rastro en la auditoría. El servicio de
 * backup se reemplaza por un servidor HTTP de mentira.
 */
class BackupControllerTest {

    private static final String CLAVE = "clave-interna";
    private static final String COPIA = "2026-10-28_15-05";

    private final AuditoriaService auditoria = mock(AuditoriaService.class);
    private final List<String> pedidos = new CopyOnWriteArrayList<>();
    private final List<String> claves = new CopyOnWriteArrayList<>();
    private HttpServer servicio;
    private BackupController controller;
    private int estadoDescarga = 200;

    @BeforeEach
    void levantarServicioFalso() throws IOException {
        servicio = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servicio.createContext("/estado", ex -> {
            registrar(ex);
            responder(ex, 200, "{\"enCurso\":false,\"driveConfigurado\":true,\"copias\":"
                    + "[{\"nombre\":\"" + COPIA + "\",\"bytes\":229376,\"destino\":\"drive\"}]}");
        });
        servicio.createContext("/descargar", ex -> {
            registrar(ex);
            responder(ex, estadoDescarga, estadoDescarga == 200 ? "CONTENIDO-DEL-TAR" : "{\"error\":\"no\"}");
        });
        servicio.createContext("/run", ex -> {
            registrar(ex);
            responder(ex, 202, "{\"ok\":true}");
        });
        servicio.start();
        controller = nuevoController(servicio.getAddress().getPort());
    }

    @AfterEach
    void apagar() {
        servicio.stop(0);
    }

    private BackupController nuevoController(int puerto) {
        return new BackupController(auditoria, "http://127.0.0.1:" + puerto + "/", CLAVE);
    }

    private void registrar(com.sun.net.httpserver.HttpExchange ex) {
        pedidos.add(ex.getRequestMethod() + " " + ex.getRequestURI());
        claves.add(String.valueOf(ex.getRequestHeaders().getFirst("X-Backup-Key")));
    }

    private static void responder(com.sun.net.httpserver.HttpExchange ex, int status, String cuerpo) throws IOException {
        byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void elEstadoSeReenviaConLaClaveInterna() {
        ResponseEntity<?> r = controller.estado();

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> cuerpo = (Map<String, Object>) r.getBody();
        assertThat(cuerpo).containsEntry("driveConfigurado", true).containsEntry("enCurso", false);
        assertThat((List<Map<String, Object>>) cuerpo.get("copias")).singleElement()
                .satisfies(c -> assertThat(c).containsEntry("nombre", COPIA).containsEntry("destino", "drive"));
        assertThat(pedidos).containsExactly("GET /estado");
        assertThat(claves).containsExactly(CLAVE);
    }

    @Test
    void hacerBackupAhoraDejaRastroEnLaAuditoria() {
        ResponseEntity<?> r = controller.ejecutar(null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(pedidos).containsExactly("POST /run");
        verify(auditoria).registrar(eq("sistema"), eq("BACKUP"), eq("Backup manual disparado"), eq("Backup"), any());
    }

    @Test
    void laCopiaSeBajaEnStreamingConNombreDeArchivoYQuedaAuditada() throws IOException {
        ResponseEntity<?> r = controller.descargar(COPIA, null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getContentType().toString()).isEqualTo("application/x-tar");
        assertThat(r.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment").contains("gs-backup-" + COPIA + ".tar");
        try (var in = ((InputStreamResource) r.getBody()).getInputStream()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("CONTENIDO-DEL-TAR");
        }
        assertThat(pedidos).containsExactly("GET /descargar?copia=" + COPIA);
        assertThat(claves).containsExactly(CLAVE);
        verify(auditoria).registrar(eq("sistema"), eq("BACKUP"), eq("Copia de seguridad descargada"), eq("Backup"), contains(COPIA));
    }

    @Test
    void unNombreQueNoEsUnaCopiaNiSiquieraLlegaAlServicio() {
        for (String malo : List.of("../../etc/passwd", "2026-10-28_15-05/../x", "2026-10-28", "", "copia", "2026-10-28_15-05.tar")) {
            ResponseEntity<?> r = controller.descargar(malo, null);

            assertThat(r.getStatusCode()).as(malo).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(pedidos).isEmpty();
        verify(auditoria, never()).registrar(any(), any(), any(), any(), any());
    }

    @Test
    void unaCopiaQueYaNoExisteDa404YNoSeAudita() {
        estadoDescarga = 404;

        ResponseEntity<?> r = controller.descargar(COPIA, null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(auditoria, never()).registrar(any(), any(), any(), any(), any());
    }

    @Test
    void siElServicioRechazaLaClaveElPanelRecibe503() {
        estadoDescarga = 401;

        ResponseEntity<?> r = controller.descargar(COPIA, null);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(auditoria, never()).registrar(any(), any(), any(), any(), any());
    }

    @Test
    void sinElContenedorDeBackupTodoDa503EnVezDeRomper() {
        int puerto = servicio.getAddress().getPort();
        servicio.stop(0);
        BackupController sinServicio = nuevoController(puerto);

        assertThat(sinServicio.estado().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(sinServicio.ejecutar(null).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(sinServicio.descargar(COPIA, null).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
