package com.gs.monolito.pedidos.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.common.security.CurrentUser;
import com.gs.monolito.pedidos.dto.AceptarRemitenteRequest;
import com.gs.monolito.pedidos.dto.OdontologoRequest;
import com.gs.monolito.pedidos.dto.PedidoRequest;
import com.gs.monolito.pedidos.dto.PedidoResponse;
import com.gs.monolito.pedidos.dto.SolicitudResponse;
import com.gs.monolito.pedidos.exception.BusinessException;
import com.gs.monolito.pedidos.exception.ResourceNotFoundException;
import com.gs.monolito.pedidos.model.DocumentoPedido;
import com.gs.monolito.pedidos.model.EscaneosPedido;
import com.gs.monolito.pedidos.model.Odontologo;
import com.gs.monolito.pedidos.model.SolicitudAdjunto;
import com.gs.monolito.pedidos.model.SolicitudEntrante;
import com.gs.monolito.pedidos.repository.DocumentoPedidoRepository;
import com.gs.monolito.pedidos.repository.EscaneosPedidoRepository;
import com.gs.monolito.pedidos.repository.OdontologoRepository;
import com.gs.monolito.pedidos.repository.SolicitudAdjuntoRepository;
import com.gs.monolito.pedidos.repository.SolicitudEntranteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Lo que hace una persona con una solicitud: convertirla en pedido (con el formulario ya revisado) o descartarla.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class SolicitudService {

    private final SolicitudEntranteRepository solicitudRepo;
    private final SolicitudAdjuntoRepository adjuntoRepo;
    private final EscaneosPedidoRepository escaneoRepo;
    private final DocumentoPedidoRepository documentoRepo;
    private final OdontologoRepository odontologoRepo;
    private final IOdontologoService odontologos;
    private final IPedidoService pedidoService;
    private final RemitenteService remitentes;
    private final AuditoriaService auditoria;
    private final Clock reloj;

    public List<SolicitudResponse> listar(SolicitudEntrante.Estado estado) {
        return solicitudRepo.findByEstadoOrderByFechaRecepcionDesc(estado).stream()
                .map(s -> SolicitudResponse.from(s, adjuntoRepo.findBySolicitudIdOrderByIdAsc(s.getId())))
                .toList();
    }

    public SolicitudResponse detalle(Long id) {
        SolicitudEntrante s = get(id);
        return SolicitudResponse.from(s, adjuntoRepo.findBySolicitudIdOrderByIdAsc(id));
    }

    public long cantidadNuevas() {
        return solicitudRepo.countByEstado(SolicitudEntrante.Estado.NUEVA);
    }

    public long cantidadSinAutorizar() {
        return solicitudRepo.countByEstado(SolicitudEntrante.Estado.SIN_AUTORIZAR);
    }

    public SolicitudAdjunto adjunto(Long solicitudId, Long adjuntoId) {
        return adjuntoRepo.findById(adjuntoId).filter(a -> a.getSolicitudId().equals(solicitudId))
                .orElseThrow(() -> new ResourceNotFoundException("Adjunto no encontrado"));
    }

    @Transactional
    public void descartar(Long id, String motivo) {
        SolicitudEntrante s = get(id);
        if (s.getEstado() != SolicitudEntrante.Estado.NUEVA && s.getEstado() != SolicitudEntrante.Estado.SIN_AUTORIZAR) {
            throw new BusinessException("La solicitud #" + id + " ya está " + s.getEstado().name().toLowerCase() + ".");
        }
        s.setEstado(SolicitudEntrante.Estado.DESCARTADA);
        s.setMotivoDescarte(motivo == null || motivo.isBlank() ? "Sin motivo" : motivo.strip());
        cerrar(s);
        auditoria.registrar(CurrentUser.usernameOrSistema(), "SOLICITUD", "Solicitud descartada", "Solicitud #" + id, s.getMotivoDescarte());
    }

    /**
     * Un mail de un remitente sin autorizar pasa a "Por revisar" porque una persona dijo quién es. Si se pide recordar
     * la dirección, los próximos mails entran directo y los demás que esperaban de esa dirección (y que no fallaron la
     * verificación) pasan juntos. Nunca se modifica la ficha de un odontólogo que ya existe: autorizar una dirección
     * es una decisión de una persona, no algo que el sistema deduzca de lo que dice el mail. Si el odontólogo todavía
     * no está cargado, la persona lo da de alta en este mismo paso ({@code nuevoOdontologo}).
     */
    @Transactional
    public SolicitudResponse aceptar(Long id, AceptarRemitenteRequest req) {
        SolicitudEntrante s = getEnEstado(id, SolicitudEntrante.Estado.SIN_AUTORIZAR);
        boolean recordar = req.recordarDireccion();
        String email = s.getRemitenteEmail();
        if (recordar && (email == null || email.isBlank())) {
            throw new BusinessException("Este mail no trae dirección de remitente, así que no se puede recordar. Aceptalo solo esta vez.");
        }

        Odontologo o = null;
        boolean odontologoCreado = false;
        if (req.tipo() == SolicitudEntrante.RemitenteTipo.OTRO) {
            if (req.nuevoOdontologo() != null) {
                throw new BusinessException("Un odontólogo nuevo se carga al aceptar como odontólogo o como su secretaria.");
            }
        } else if (req.nuevoOdontologo() != null) {
            o = crearOdontologo(req, email);
            odontologoCreado = true;
        } else {
            if (req.odontologoId() == null) throw new BusinessException("Elegí a qué odontólogo corresponde, o cargalo como nuevo.");
            o = odontologoRepo.findById(req.odontologoId()).filter(x -> Boolean.TRUE.equals(x.getActivo()))
                    .orElseThrow(() -> new ResourceNotFoundException("Odontólogo", req.odontologoId()));
        }
        String etiqueta = RemitenteService.etiqueta(req.tipo(), o, req.aclaracion());
        // Un odontólogo recién cargado con el mail de quien escribe ya queda reconocido por su ficha: no hace falta una regla aparte.
        boolean cubreLaFicha = odontologoCreado && req.tipo() == SolicitudEntrante.RemitenteTipo.ODONTOLOGO && o.getEmail() != null;

        int juntas = 0;
        if (recordar) {
            if (!cubreLaFicha) remitentes.autorizar(email, req.tipo(), o == null ? null : o.getId(), req.aclaracion());
            for (SolicitudEntrante otra : solicitudRepo.findByRemitenteEmailAndEstado(email, SolicitudEntrante.Estado.SIN_AUTORIZAR)) {
                if (!otra.getId().equals(s.getId()) && !Boolean.FALSE.equals(otra.getRemitenteVerificado())) {
                    promover(otra, req.tipo(), o, etiqueta);
                    juntas++;
                }
            }
        }
        promover(s, req.tipo(), o, etiqueta);
        auditoria.registrar(CurrentUser.usernameOrSistema(), "SOLICITUD", "Mail sin autorizar aceptado", "Solicitud #" + id,
                (email == null ? "?" : email) + " · " + etiqueta + (recordar ? " · dirección recordada" : " · solo esta vez")
                        + (odontologoCreado ? " · odontólogo nuevo cargado" : "")
                        + (juntas > 0 ? " · pasaron " + juntas + " más" : ""));
        log.info("[CORREO] Solicitud #{} aceptada como {} ({}){}.", id, req.tipo(), etiqueta, recordar ? ", dirección recordada" : "");
        return detalle(id);
    }

    /** Da de alta al odontólogo que todavía no estaba cargado; si quien escribe es él, su ficha lleva ese mail. */
    private Odontologo crearOdontologo(AceptarRemitenteRequest req, String emailRemitente) {
        AceptarRemitenteRequest.NuevoOdontologo n = req.nuevoOdontologo();
        OdontologoRequest ficha = new OdontologoRequest();
        ficha.setNombre(n.nombre().strip());
        ficha.setClinica(n.clinica() == null || n.clinica().isBlank() ? null : n.clinica().strip());
        ficha.setTelefono(n.telefono() == null || n.telefono().isBlank() ? null : n.telefono().strip());
        if (req.tipo() == SolicitudEntrante.RemitenteTipo.ODONTOLOGO && emailRemitente != null && emailRemitente.length() <= 100) {
            ficha.setEmail(emailRemitente.strip().toLowerCase());
        }
        Long creadoId = odontologos.crear(ficha).id();
        return odontologoRepo.findById(creadoId).orElseThrow(() -> new ResourceNotFoundException("Odontólogo", creadoId));
    }

    /** Bloquea al remitente de un mail sin autorizar: se descartan todos los suyos y los próximos se ignoran. */
    @Transactional
    public void bloquearRemitente(Long id) {
        SolicitudEntrante s = getEnEstado(id, SolicitudEntrante.Estado.SIN_AUTORIZAR);
        if (s.getRemitenteEmail() == null || s.getRemitenteEmail().isBlank()) {
            throw new BusinessException("Este mail no trae dirección de remitente: solo se puede descartar.");
        }
        remitentes.bloquear(s.getRemitenteEmail());
    }

    private void promover(SolicitudEntrante s, SolicitudEntrante.RemitenteTipo tipo, Odontologo o, String etiqueta) {
        s.setEstado(SolicitudEntrante.Estado.NUEVA);
        s.setRemitenteTipo(tipo);
        s.setRemitenteEtiqueta(etiqueta);
        s.setOdontologoId(o == null ? null : o.getId());
        s.setOdontologoNombre(o == null ? null : o.getNombre());
        s.setMotivoCuarentena(null);
        solicitudRepo.save(s);
    }

    /**
     * Crea el pedido con los datos que confirmó quien revisó, le pasa los adjuntos (sin copiar archivos: se reutiliza el
     * objeto de MinIO) y cierra la solicitud.
     */
    @Transactional
    public PedidoResponse convertir(Long id, PedidoRequest datos) {
        SolicitudEntrante s = getNueva(id);

        String origen = "Recibido por mail de " + (s.getRemitenteEmail() == null ? "?" : s.getRemitenteEmail());
        String obs = datos.getObservaciones() == null || datos.getObservaciones().isBlank() ? origen : datos.getObservaciones().strip() + " | " + origen;
        datos.setObservaciones(obs.length() <= 1000 ? obs : obs.substring(0, 999) + "…");

        PedidoResponse pedido = pedidoService.crear(datos);
        String quien = CurrentUser.usernameOrSistema();

        for (SolicitudAdjunto a : adjuntoRepo.findBySolicitudIdOrderByIdAsc(id)) {
            if (a.getTipo() == SolicitudAdjunto.Tipo.ESCANEO) {
                escaneoRepo.save(EscaneosPedido.builder()
                        .pedidoId(pedido.id()).objectKey(a.getObjectKey()).fileName(a.getFileName())
                        .contentType(a.getContentType()).tamanioBytes(a.getTamanioBytes())
                        .descripcion(recortar("Recibido por mail: " + (s.getAsunto() == null ? "" : s.getAsunto()), 255))
                        .subidoPor("correo")
                        .formato(a.getFormato()).triangulos(a.getTriangulos()).vertices(a.getVertices())
                        .dimXMm(a.getDimXMm()).dimYMm(a.getDimYMm()).dimZMm(a.getDimZMm())
                        .sha256(a.getSha256()).advertencias(a.getAdvertencias())
                        .build());
            } else {
                documentoRepo.save(DocumentoPedido.builder()
                        .pedidoId(pedido.id()).objectKey(a.getObjectKey()).fileName(a.getFileName())
                        .contentType(a.getContentType()).tamanioBytes(a.getTamanioBytes()).subidoPor("correo")
                        .build());
            }
        }

        s.setEstado(SolicitudEntrante.Estado.CONVERTIDA);
        s.setPedidoId(pedido.id());
        cerrar(s);
        auditoria.registrar(quien, "SOLICITUD", "Solicitud convertida en pedido", "Solicitud #" + id,
                "Pedido " + pedido.nroPedido() + " · " + pedido.odontologoNombre());
        log.info("[CORREO] Solicitud #{} convertida en el pedido {}", id, pedido.nroPedido());
        return pedido;
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void cerrar(SolicitudEntrante s) {
        s.setResueltoPor(CurrentUser.usernameOrSistema());
        s.setFechaResolucion(LocalDateTime.now(reloj));
        solicitudRepo.save(s);
    }

    private SolicitudEntrante get(Long id) {
        return solicitudRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Solicitud no encontrada: " + id));
    }

    private SolicitudEntrante getNueva(Long id) {
        return getEnEstado(id, SolicitudEntrante.Estado.NUEVA);
    }

    private SolicitudEntrante getEnEstado(Long id, SolicitudEntrante.Estado esperado) {
        SolicitudEntrante s = get(id);
        if (s.getEstado() != esperado) {
            throw new BusinessException("La solicitud #" + id + " ya está " + nombreEstado(s.getEstado()) + ".");
        }
        return s;
    }

    private static String nombreEstado(SolicitudEntrante.Estado e) {
        return e == SolicitudEntrante.Estado.SIN_AUTORIZAR ? "sin autorizar" : e.name().toLowerCase();
    }

    private static String recortar(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
