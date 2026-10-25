package com.gs.monolito.pedidos.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.catalogo.repository.TipoTrabajoRepository;
import com.gs.monolito.pedidos.config.CorreoProperties;
import com.gs.monolito.pedidos.model.SolicitudAdjunto;
import com.gs.monolito.pedidos.model.SolicitudEntrante;
import com.gs.monolito.pedidos.repository.SolicitudAdjuntoRepository;
import com.gs.monolito.pedidos.repository.SolicitudEntranteRepository;
import com.gs.monolito.pedidos.service.ExtractorSolicitud.ItemCatalogo;
import com.gs.monolito.pedidos.service.ExtractorSolicitud.Sugerencia;
import com.gs.monolito.pedidos.service.RemitenteService.Clasificacion;
import com.gs.monolito.pedidos.service.RemitenteService.Destino;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Convierte un mail ya leído en una {@link SolicitudEntrante}: según quién lo mande ({@link RemitenteService}) entra a
 * "Por revisar" o queda aparte como "sin autorizar"; adelanta el
 * formulario con lo que el {@link ExtractorSolicitud} pudo sacar del texto, y guarda los adjuntos (los escaneos 3D
 * pasan antes por el {@link MallaInspector}). No crea el pedido: eso lo decide una persona.
 *
 * <p><b>Idempotente por Message-ID:</b> el mismo mail devuelve la misma solicitud y no crea nada. Es lo que permite
 * que, si falla el marcado como leído, el siguiente ciclo del lector no duplique el pedido (el scraper anterior sí lo
 * hacía).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecepcionSolicitudService {

    private static final Set<String> ESCANEOS = Set.of("stl", "obj", "ply");
    private static final Map<String, String> DOCUMENTOS = Map.of(
            "pdf", "application/pdf", "jpg", "image/jpeg", "jpeg", "image/jpeg",
            "png", "image/png", "gif", "image/gif", "webp", "image/webp");

    private final SolicitudEntranteRepository solicitudRepo;
    private final SolicitudAdjuntoRepository adjuntoRepo;
    private final TipoTrabajoRepository tipoTrabajoRepo;
    private final ExtractorSolicitud extractor;
    private final MallaInspector mallaInspector;
    private final PedidosDocumentoStorageService storage;
    private final AuditoriaService auditoria;
    private final CorreoProperties props;
    private final Clock reloj;

    /**
     * Un adjunto tal como salió del mail. {@code datos} es null si no se llegó a leer (por ejemplo, por pesado) y
     * entonces {@code omitidoPor} dice por qué.
     */
    public record Adjunto(String nombre, byte[] datos, String omitidoPor) {}

    public record DatosCorreo(String messageId, String remitenteEmail, String remitenteNombre, String asunto,
                              String cuerpo, LocalDateTime fecha, List<Adjunto> adjuntos) {}

    /** @param nueva false si el mail ya estaba cargado (no se creó nada) */
    public record Resultado(SolicitudEntrante solicitud, boolean nueva) {}

    /**
     * @param c lo que {@link RemitenteService} decidió sobre el remitente; no puede ser IGNORAR (eso ni se guarda). Si
     *          es SIN_AUTORIZAR el mail queda aparte: se guarda un cuerpo recortado y ningún adjunto, solo anotados.
     */
    @Transactional
    public Resultado registrar(DatosCorreo d, Clasificacion c) {
        if (c.destino() == Destino.IGNORAR) {
            throw new IllegalArgumentException("Un mail ignorado no se registra: " + c.motivo());
        }
        Optional<SolicitudEntrante> existente = solicitudRepo.findByMessageId(d.messageId());
        if (existente.isPresent()) {
            log.info("[CORREO] El mail {} ya estaba cargado (solicitud #{}): no se vuelve a crear.", d.messageId(), existente.get().getId());
            return new Resultado(existente.get(), false);
        }

        boolean conocido = c.destino() == Destino.CONOCIDO;
        String cuerpo = recortar(d.cuerpo(), conocido ? props.getMaxCuerpo() : props.getMaxCuerpoCuarentena());
        List<ItemCatalogo> catalogo = tipoTrabajoRepo.findByActivoTrue().stream()
                .map(t -> new ItemCatalogo(t.getId(), t.getNombre())).toList();
        Sugerencia sug = extractor.extraer(d.asunto(), cuerpo, LocalDate.now(reloj), catalogo);

        SolicitudEntrante s = solicitudRepo.save(SolicitudEntrante.builder()
                .messageId(recortar(d.messageId(), 255))
                .remitenteEmail(recortar(d.remitenteEmail(), 150))
                .remitenteNombre(recortar(d.remitenteNombre(), 150))
                .asunto(recortar(d.asunto(), 255))
                .cuerpo(cuerpo)
                .fechaMensaje(d.fecha())
                .fechaRecepcion(LocalDateTime.now(reloj))
                .estado(conocido ? SolicitudEntrante.Estado.NUEVA : SolicitudEntrante.Estado.SIN_AUTORIZAR)
                .odontologoId(c.odontologoId())
                .odontologoNombre(c.odontologoNombre())
                .remitenteTipo(c.tipo())
                .remitenteEtiqueta(recortar(c.etiqueta(), 150))
                .motivoCuarentena(recortar(c.motivo(), 255))
                .remitenteVerificado(c.verificado())
                .sugPaciente(sug.paciente())
                .sugTrabajo(sug.trabajo())
                .sugCatalogoTrabajoId(sug.catalogoTrabajoId())
                .sugFechaEntrega(sug.fechaEntrega())
                .sugPrioridad(sug.prioridad())
                .sugPrecio(sug.precio())
                .sugObservaciones(sug.observaciones())
                .build());

        guardarAdjuntos(s, conocido ? d.adjuntos() : sinGuardar(d.adjuntos()));

        log.info("[CORREO] Solicitud #{} de {} ({}){}: {} adjunto(s){}", s.getId(), d.remitenteEmail(),
                c.etiqueta() == null ? "remitente sin reconocer" : c.etiqueta(), conocido ? "" : " SIN AUTORIZAR",
                adjuntoRepo.countBySolicitudId(s.getId()), s.getNotaAdjuntos() == null ? "" : ", con avisos");
        auditoria.registrar("correo", "SOLICITUD", conocido ? "Solicitud recibida por mail" : "Mail de un remitente sin autorizar",
                "Solicitud #" + s.getId(), d.remitenteEmail() + " · " + recortar(d.asunto(), 100));
        return new Resultado(s, true);
    }

    /** De un remitente sin autorizar no se baja ni se abre nada: solo se anota qué traía. */
    private static List<Adjunto> sinGuardar(List<Adjunto> adjuntos) {
        return adjuntos.stream()
                .map(a -> new Adjunto(a.nombre(), null, "no se guardó porque el remitente todavía no está autorizado"))
                .toList();
    }

    // ── Adjuntos ─────────────────────────────────────────────────────

    /**
     * Guarda los adjuntos que sirven y anota los que no. Si algo falla a mitad, los archivos ya subidos a MinIO se
     * borran (la base se revierte sola) para no dejar objetos huérfanos.
     */
    private void guardarAdjuntos(SolicitudEntrante s, List<Adjunto> adjuntos) {
        List<String> omitidos = new ArrayList<>();
        List<String> subidos = new ArrayList<>();
        try {
            int guardados = 0;
            for (Adjunto a : adjuntos) {
                String nombre = NombresArchivo.seguro(a.nombre());
                if (a.omitidoPor() != null) {
                    omitidos.add(nombre + ": " + a.omitidoPor());
                    continue;
                }
                if (guardados >= props.getMaxAdjuntos()) {
                    omitidos.add(nombre + ": se superó el máximo de " + props.getMaxAdjuntos() + " adjuntos");
                    continue;
                }
                SolicitudAdjunto guardado = procesar(s, nombre, a.datos(), omitidos, subidos);
                if (guardado != null) guardados++;
            }
        } catch (RuntimeException e) {
            subidos.forEach(storage::eliminar);
            throw e;
        }
        if (!omitidos.isEmpty()) {
            s.setNotaAdjuntos(recortar(String.join(" | ", omitidos), 600));
            solicitudRepo.save(s);
        }
    }

    private SolicitudAdjunto procesar(SolicitudEntrante s, String nombre, byte[] datos, List<String> omitidos, List<String> subidos) {
        String ext = NombresArchivo.extension(nombre);
        if (datos == null || datos.length == 0) {
            omitidos.add(nombre + ": está vacío");
            return null;
        }
        SolicitudAdjunto.SolicitudAdjuntoBuilder b = SolicitudAdjunto.builder()
                .solicitudId(s.getId()).fileName(nombre).tamanioBytes((long) datos.length);
        String contentType;
        if (ESCANEOS.contains(ext)) {
            try {
                MallaInspector.InformeMalla i = mallaInspector.inspeccionar(new ByteArrayInputStream(datos), datos.length, ext);
                contentType = "application/octet-stream";
                b.tipo(SolicitudAdjunto.Tipo.ESCANEO).sha256(i.sha256()).formato(i.formato()).triangulos(i.triangulos())
                        .vertices(i.vertices()).dimXMm(i.dimX()).dimYMm(i.dimY()).dimZMm(i.dimZ())
                        .advertencias(i.advertencias().isEmpty() ? null : recortar(String.join(" | ", i.advertencias()), 600));
            } catch (MallaInspector.MallaInvalidaException e) {
                omitidos.add(nombre + ": " + e.getMessage());
                return null;
            } catch (IOException e) {
                omitidos.add(nombre + ": no se pudo leer");
                return null;
            }
        } else if (DOCUMENTOS.containsKey(ext)) {
            if (!firmaCorresponde(ext, datos)) {
                omitidos.add(nombre + ": el contenido no coincide con su extensión (." + ext + ")");
                return null;
            }
            contentType = DOCUMENTOS.get(ext);
            b.tipo(SolicitudAdjunto.Tipo.DOCUMENTO);
        } else {
            omitidos.add(nombre + ": tipo de archivo no permitido (se aceptan STL, OBJ, PLY, PDF y fotos)");
            return null;
        }

        String clave = storage.subirStream(new ByteArrayInputStream(datos), datos.length, contentType, nombre, s.getId(), "solicitudes");
        if (clave == null) {
            omitidos.add(nombre + ": no se pudo guardar (almacenamiento no disponible)");
            return null;
        }
        subidos.add(clave);
        return adjuntoRepo.save(b.objectKey(clave).contentType(contentType).build());
    }

    /** Las firmas de las fotos y PDF: un mail puede traer cualquier cosa con cualquier extensión. */
    static boolean firmaCorresponde(String ext, byte[] d) {
        return switch (ext) {
            case "pdf" -> empieza(d, 0, new byte[]{'%', 'P', 'D', 'F', '-'});
            case "png" -> empieza(d, 0, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
            case "jpg", "jpeg" -> empieza(d, 0, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
            case "gif" -> empieza(d, 0, new byte[]{'G', 'I', 'F', '8'});
            case "webp" -> empieza(d, 0, new byte[]{'R', 'I', 'F', 'F'}) && empieza(d, 8, new byte[]{'W', 'E', 'B', 'P'});
            default -> false;
        };
    }

    private static boolean empieza(byte[] d, int desde, byte[] firma) {
        if (d.length < desde + firma.length) return false;
        for (int i = 0; i < firma.length; i++) if (d[desde + i] != firma[i]) return false;
        return true;
    }

    private static String recortar(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
