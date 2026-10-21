package com.gs.monolito.pedidos.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.common.security.CurrentUser;
import com.gs.monolito.pedidos.dto.PedidoRequest;
import com.gs.monolito.pedidos.dto.PedidoResponse;
import com.gs.monolito.pedidos.dto.SolicitudResponse;
import com.gs.monolito.pedidos.exception.BusinessException;
import com.gs.monolito.pedidos.exception.ResourceNotFoundException;
import com.gs.monolito.pedidos.model.DocumentoPedido;
import com.gs.monolito.pedidos.model.EscaneosPedido;
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
    private final IPedidoService pedidoService;
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

    public SolicitudAdjunto adjunto(Long solicitudId, Long adjuntoId) {
        return adjuntoRepo.findById(adjuntoId).filter(a -> a.getSolicitudId().equals(solicitudId))
                .orElseThrow(() -> new ResourceNotFoundException("Adjunto no encontrado"));
    }

    @Transactional
    public void descartar(Long id, String motivo) {
        SolicitudEntrante s = getNueva(id);
        s.setEstado(SolicitudEntrante.Estado.DESCARTADA);
        s.setMotivoDescarte(motivo == null || motivo.isBlank() ? "Sin motivo" : motivo.strip());
        cerrar(s);
        auditoria.registrar(CurrentUser.usernameOrSistema(), "SOLICITUD", "Solicitud descartada", "Solicitud #" + id, s.getMotivoDescarte());
    }

    /**
     * Crea el pedido con los datos que confirmó quien revisó, le pasa los adjuntos (sin copiar archivos: se reutiliza el
     * objeto de MinIO) y cierra la solicitud. Si el odontólogo elegido no tenía email, se lo aprende del remitente: la
     * próxima vez que escriba, se lo reconoce solo.
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

        aprenderEmail(pedido.odontologoId(), s.getRemitenteEmail());

        s.setEstado(SolicitudEntrante.Estado.CONVERTIDA);
        s.setPedidoId(pedido.id());
        cerrar(s);
        auditoria.registrar(quien, "SOLICITUD", "Solicitud convertida en pedido", "Solicitud #" + id,
                "Pedido " + pedido.nroPedido() + " · " + pedido.odontologoNombre());
        log.info("[CORREO] Solicitud #{} convertida en el pedido {}", id, pedido.nroPedido());
        return pedido;
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void aprenderEmail(Long odontologoId, String email) {
        if (odontologoId == null || email == null || email.isBlank()) return;
        odontologoRepo.findById(odontologoId).ifPresent(o -> {
            if (o.getEmail() == null || o.getEmail().isBlank()) {
                o.setEmail(email.strip().toLowerCase());
                odontologoRepo.save(o);
            }
        });
    }

    private void cerrar(SolicitudEntrante s) {
        s.setResueltoPor(CurrentUser.usernameOrSistema());
        s.setFechaResolucion(LocalDateTime.now(reloj));
        solicitudRepo.save(s);
    }

    private SolicitudEntrante get(Long id) {
        return solicitudRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Solicitud no encontrada: " + id));
    }

    private SolicitudEntrante getNueva(Long id) {
        SolicitudEntrante s = get(id);
        if (s.getEstado() != SolicitudEntrante.Estado.NUEVA) {
            throw new BusinessException("La solicitud #" + id + " ya está " + s.getEstado().name().toLowerCase() + ".");
        }
        return s;
    }

    private static String recortar(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
