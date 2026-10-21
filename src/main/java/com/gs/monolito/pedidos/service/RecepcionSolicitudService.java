package com.gs.monolito.pedidos.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.catalogo.repository.TipoTrabajoRepository;
import com.gs.monolito.pedidos.config.CorreoProperties;
import com.gs.monolito.pedidos.model.Odontologo;
import com.gs.monolito.pedidos.model.SolicitudAdjunto;
import com.gs.monolito.pedidos.model.SolicitudEntrante;
import com.gs.monolito.pedidos.repository.OdontologoRepository;
import com.gs.monolito.pedidos.repository.SolicitudAdjuntoRepository;
import com.gs.monolito.pedidos.repository.SolicitudEntranteRepository;
import com.gs.monolito.pedidos.service.ExtractorSolicitud.ItemCatalogo;
import com.gs.monolito.pedidos.service.ExtractorSolicitud.Sugerencia;
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
 * Convierte un mail ya leído en una {@link SolicitudEntrante}: reconoce al odontólogo por su email, adelanta el
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
    private final OdontologoRepository odontologoRepo;
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

    @Transactional
    public Resultado registrar(DatosCorreo d) {
        Optional<SolicitudEntrante> existente = solicitudRepo.findByMessageId(d.messageId());
        if (existente.isPresent()) {
            log.info("[CORREO] El mail {} ya estaba cargado (solicitud #{}): no se vuelve a crear.", d.messageId(), existente.get().getId());
            return new Resultado(existente.get(), false);
        }

        Odontologo odontologo = reconocerOdontologo(d.remitenteEmail());
        List<ItemCatalogo> catalogo = tipoTrabajoRepo.findByActivoTrue().stream()
                .map(t -> new ItemCatalogo(t.getId(), t.getNombre())).toList();
        Sugerencia sug = extractor.extraer(d.asunto(), d.cuerpo(), LocalDate.now(reloj), catalogo);

        SolicitudEntrante s = solicitudRepo.save(SolicitudEntrante.builder()
                .messageId(recortar(d.messageId(), 255))
                .remitenteEmail(recortar(d.remitenteEmail(), 150))
                .remitenteNombre(recortar(d.remitenteNombre(), 150))
                .asunto(recortar(d.asunto(), 255))
                .cuerpo(recortar(d.cuerpo(), props.getMaxCuerpo()))
                .fechaMensaje(d.fecha())
                .fechaRecepcion(LocalDateTime.now(reloj))
                .odontologoId(odontologo == null ? null : odontologo.getId())
                .odontologoNombre(odontologo == null ? null : odontologo.getNombre())
                .sugPaciente(sug.paciente())
                .sugTrabajo(sug.trabajo())
                .sugCatalogoTrabajoId(sug.catalogoTrabajoId())
                .sugFechaEntrega(sug.fechaEntrega())
                .sugPrioridad(sug.prioridad())
                .sugPrecio(sug.precio())
                .sugObservaciones(sug.observaciones())
                .build());

        guardarAdjuntos(s, d.adjuntos());

        log.info("[CORREO] Solicitud #{} de {} ({}): {} adjunto(s){}", s.getId(), d.remitenteEmail(),
                odontologo == null ? "remitente sin reconocer" : odontologo.getNombre(),
                adjuntoRepo.countBySolicitudId(s.getId()), s.getNotaAdjuntos() == null ? "" : ", con avisos");
        auditoria.registrar("correo", "SOLICITUD", "Solicitud recibida por mail", "Solicitud #" + s.getId(),
                d.remitenteEmail() + " · " + recortar(d.asunto(), 100));
        return new Resultado(s, true);
    }

    private Odontologo reconocerOdontologo(String email) {
        if (email == null || email.isBlank()) return null;
        List<Odontologo> coinciden = odontologoRepo.findByActivoTrueAndEmailIgnoreCase(email.trim());
        // Si la casilla la comparten varios odontólogos no se adivina cuál es: lo elige quien revisa.
        return coinciden.size() == 1 ? coinciden.get(0) : null;
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
