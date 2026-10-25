package com.gs.monolito.finanzas.dto;

import com.gs.monolito.finanzas.model.EstadoRegistroBot;
import com.gs.monolito.finanzas.model.FuentePago;
import com.gs.monolito.finanzas.model.RegistroPagoBot;
import com.gs.monolito.finanzas.model.TipoReceptorBot;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Item del historial del bot (lo que ve el front en la tabla). */
public record RegistroPagoBotResponse(
        Long id,
        LocalDateTime fechaHora,
        BigDecimal monto,
        String idOperacion,
        String emisor,
        String receptorNombre,
        TipoReceptorBot tipoReceptor,
        Long receptorId,
        String receptorResuelto,
        EstadoRegistroBot estado,
        String mensaje,
        String cargadoPorNombre,
        String grupoOrigen,
        boolean tieneComprobante,
        FuentePago fuente,
        /** Motivos por los que quedó pendiente de revisión (separados por " | "), o null. */
        String motivosRevision,
        /** true si el bot reenvió un mensaje que el sistema ya había procesado: no se creó nada nuevo. */
        boolean repetido,
        /**
         * Solo en los pendientes: odontólogos con deuda que podrían ser quien pagó, cuando el nombre del emisor no
         * alcanza para estar seguro. Vacío si no hace falta elegir.
         */
        java.util.List<CandidatoEmisor> candidatosEmisor
) {
    public static RegistroPagoBotResponse from(RegistroPagoBot r) {
        return new RegistroPagoBotResponse(
                r.getId(),
                r.getFechaHora(),
                r.getMonto(),
                r.getIdOperacion(),
                r.getEmisor(),
                r.getReceptorNombre(),
                r.getTipoReceptor(),
                r.getReceptorId(),
                r.getReceptorResuelto(),
                r.getEstado(),
                r.getMensaje(),
                r.getCargadoPorNombre(),
                r.getGrupoOrigen(),
                r.getComprobanteUrl() != null && !r.getComprobanteUrl().isBlank(),
                r.getFuente(),
                r.getMotivosRevision(),
                false,
                java.util.List.of()
        );
    }

    /** El mismo registro, marcado como "ya procesado" (idempotencia por ID de mensaje). */
    public static RegistroPagoBotResponse repetido(RegistroPagoBot r) {
        RegistroPagoBotResponse base = from(r);
        return new RegistroPagoBotResponse(base.id(), base.fechaHora(), base.monto(), base.idOperacion(), base.emisor(),
                base.receptorNombre(), base.tipoReceptor(), base.receptorId(), base.receptorResuelto(), base.estado(),
                base.mensaje(), base.cargadoPorNombre(), base.grupoOrigen(), base.tieneComprobante(), base.fuente(),
                base.motivosRevision(), true, java.util.List.of());
    }

    /** El mismo registro con los odontólogos que podrían ser quien pagó (para que quien aprueba elija). */
    public RegistroPagoBotResponse conCandidatos(java.util.List<CandidatoEmisor> candidatos) {
        return new RegistroPagoBotResponse(id, fechaHora, monto, idOperacion, emisor, receptorNombre, tipoReceptor,
                receptorId, receptorResuelto, estado, mensaje, cargadoPorNombre, grupoOrigen, tieneComprobante, fuente,
                motivosRevision, repetido, candidatos);
    }
}
