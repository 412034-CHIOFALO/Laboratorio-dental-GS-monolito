package com.gs.monolito.pedidos.dto;

import com.gs.monolito.pedidos.model.Prioridad;
import com.gs.monolito.pedidos.model.SolicitudAdjunto;
import com.gs.monolito.pedidos.model.SolicitudEntrante;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Una solicitud entrante tal como la ve el panel: lo que llegó, lo que el sistema sugiere para el formulario y los
 * adjuntos. {@code identificado} dice si se reconoció al odontólogo por su email.
 */
public record SolicitudResponse(
        Long id,
        SolicitudEntrante.Canal canal,
        SolicitudEntrante.Estado estado,
        String remitenteEmail,
        String remitenteNombre,
        String asunto,
        String cuerpo,
        LocalDateTime fechaMensaje,
        LocalDateTime fechaRecepcion,
        boolean identificado,
        Long odontologoId,
        String odontologoNombre,
        SolicitudEntrante.RemitenteTipo remitenteTipo,
        String remitenteEtiqueta,
        String motivoCuarentena,
        Boolean remitenteVerificado,
        Sugerencia sugerencia,
        String notaAdjuntos,
        Long pedidoId,
        String motivoDescarte,
        String resueltoPor,
        LocalDateTime fechaResolucion,
        List<Adjunto> adjuntos
) {
    public record Sugerencia(String paciente, String trabajo, Long catalogoTrabajoId, LocalDate fechaEntrega,
                             Prioridad prioridad, BigDecimal precio, String observaciones) {}

    public record Adjunto(Long id, String fileName, Long tamanioBytes, SolicitudAdjunto.Tipo tipo, String formato,
                          Long triangulos, BigDecimal dimXMm, BigDecimal dimYMm, BigDecimal dimZMm, List<String> advertencias) {

        public static Adjunto from(SolicitudAdjunto a) {
            return new Adjunto(a.getId(), a.getFileName(), a.getTamanioBytes(), a.getTipo(), a.getFormato(), a.getTriangulos(),
                    a.getDimXMm(), a.getDimYMm(), a.getDimZMm(),
                    a.getAdvertencias() == null || a.getAdvertencias().isBlank() ? List.of() : List.of(a.getAdvertencias().split(" \\| ")));
        }
    }

    public static SolicitudResponse from(SolicitudEntrante s, List<SolicitudAdjunto> adjuntos) {
        return new SolicitudResponse(s.getId(), s.getCanal(), s.getEstado(), s.getRemitenteEmail(), s.getRemitenteNombre(),
                s.getAsunto(), s.getCuerpo(), s.getFechaMensaje(), s.getFechaRecepcion(),
                s.getOdontologoId() != null, s.getOdontologoId(), s.getOdontologoNombre(),
                s.getRemitenteTipo(), s.getRemitenteEtiqueta(), s.getMotivoCuarentena(), s.getRemitenteVerificado(),
                new Sugerencia(s.getSugPaciente(), s.getSugTrabajo(), s.getSugCatalogoTrabajoId(), s.getSugFechaEntrega(),
                        s.getSugPrioridad(), s.getSugPrecio(), s.getSugObservaciones()),
                s.getNotaAdjuntos(), s.getPedidoId(), s.getMotivoDescarte(), s.getResueltoPor(), s.getFechaResolucion(),
                adjuntos.stream().map(Adjunto::from).toList());
    }
}
