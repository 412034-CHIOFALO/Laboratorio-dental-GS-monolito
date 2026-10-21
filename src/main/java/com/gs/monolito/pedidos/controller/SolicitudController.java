package com.gs.monolito.pedidos.controller;

import com.gs.monolito.pedidos.dto.PedidoRequest;
import com.gs.monolito.pedidos.dto.PedidoResponse;
import com.gs.monolito.pedidos.dto.SolicitudResponse;
import com.gs.monolito.pedidos.model.SolicitudAdjunto;
import com.gs.monolito.pedidos.model.SolicitudEntrante;
import com.gs.monolito.pedidos.service.EstadoCorreo;
import com.gs.monolito.pedidos.service.PedidosDocumentoStorageService;
import com.gs.monolito.pedidos.service.SolicitudService;
import com.gs.monolito.pedidos.service.TiposArchivo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Solicitudes entrantes (hoy, mails de los odontólogos). Quedan bajo {@code /api/pedidos/solicitudes} para heredar las
 * reglas de acceso de pedidos: ver y descargar lo pueden hacer ADMIN, ADMINISTRATIVO y TECNICO; convertir en pedido y
 * descartar, solo ADMIN y ADMINISTRATIVO.
 */
@Tag(name = "Solicitudes entrantes", description = "Pedidos que llegaron por mail y esperan que alguien los revise: se convierten en pedido o se descartan")
@RestController
@RequestMapping("/api/pedidos/solicitudes")
@RequiredArgsConstructor
@Validated
public class SolicitudController {

    private final SolicitudService service;
    private final EstadoCorreo estadoCorreo;
    private final PedidosDocumentoStorageService storage;

    @Operation(summary = "Lista las solicitudes de un estado", description = "Por defecto las NUEVAS (las que esperan revisión), de la más reciente a la más vieja.")
    @GetMapping
    public ResponseEntity<List<SolicitudResponse>> listar(
            @RequestParam(defaultValue = "NUEVA") SolicitudEntrante.Estado estado) {
        return ResponseEntity.ok(service.listar(estado));
    }

    @Operation(summary = "Cuántas solicitudes esperan revisión", description = "Para el aviso del menú.")
    @GetMapping("/cantidad")
    public ResponseEntity<Map<String, Long>> cantidad() {
        return ResponseEntity.ok(Map.of("nuevas", service.cantidadNuevas()));
    }

    @Operation(summary = "Estado de la lectura de la casilla", description = "Cuándo se leyó por última vez, si hubo un error y cuántos mails se recibieron, ignoraron o fallaron.")
    @GetMapping("/estado-correo")
    public ResponseEntity<EstadoCorreo.Vista> estadoCorreo() {
        return ResponseEntity.ok(estadoCorreo.vista());
    }

    @Operation(summary = "Detalle de una solicitud")
    @GetMapping("/{id}")
    public ResponseEntity<SolicitudResponse> detalle(@PathVariable @Positive Long id) {
        return ResponseEntity.ok(service.detalle(id));
    }

    @Operation(summary = "Descarga un adjunto de la solicitud", description = "Se sirve a través del backend; los escaneos 3D salen siempre como descarga.")
    @GetMapping("/{id}/adjuntos/{adjuntoId}/archivo")
    public ResponseEntity<InputStreamResource> adjunto(@PathVariable @Positive Long id, @PathVariable @Positive Long adjuntoId) {
        SolicitudAdjunto a = service.adjunto(id, adjuntoId);
        InputStream in = storage.descargar(a.getObjectKey());
        if (in == null) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        ResponseEntity.BodyBuilder r = ResponseEntity.ok();
        if (a.getTamanioBytes() != null) r.contentLength(a.getTamanioBytes());
        return r.contentType(TiposArchivo.mediaTypePara(a.getFileName()))
                .header(HttpHeaders.CONTENT_DISPOSITION, TiposArchivo.contentDisposition(a.getFileName()))
                .body(new InputStreamResource(in));
    }

    @Operation(summary = "Convierte la solicitud en pedido",
               description = "Recibe el formulario del pedido ya revisado (lo que se sugirió puede editarse), crea el pedido, le pasa los adjuntos y cierra la solicitud.")
    @PostMapping("/{id}/convertir")
    public ResponseEntity<PedidoResponse> convertir(@PathVariable @Positive Long id, @Valid @RequestBody PedidoRequest pedido) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.convertir(id, pedido));
    }

    @Operation(summary = "Descarta la solicitud", description = "Para lo que no es un pedido (consultas, spam). Queda registrado el motivo.")
    @PostMapping("/{id}/descartar")
    public ResponseEntity<Void> descartar(@PathVariable @Positive Long id, @Valid @RequestBody(required = false) Descarte cuerpo) {
        service.descartar(id, cuerpo == null ? null : cuerpo.motivo());
        return ResponseEntity.noContent().build();
    }

    public record Descarte(@Size(max = 255, message = "El motivo no puede superar los 255 caracteres") String motivo) {}
}
