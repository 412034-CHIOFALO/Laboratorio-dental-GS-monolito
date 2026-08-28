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
        FuentePago fuente
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
                r.getFuente()
        );
    }
}
