package com.gs.monolito.pedidos.dto;

import com.gs.monolito.pedidos.model.EstadoPedido;
import com.gs.monolito.pedidos.model.Pedido;
import com.gs.monolito.pedidos.model.Prioridad;
import com.gs.monolito.pedidos.util.DiasHabiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record PedidoResponse(
        Long id,
        String nroPedido,
        Long odontologoId,
        String odontologoNombre,
        String paciente,
        Long catalogoTrabajoId,
        String trabajo,
        Long tecnicoId,
        String tecnicoNombre,
        LocalDate fechaEntrega,
        EstadoPedido estado,
        Prioridad prioridad,
        BigDecimal precioAcordado,
        String observaciones,
        LocalDate fechaEntregaReal,
        String retiradoPor,
        String observacionesEntrega,
        LocalDateTime fechaCreacion,
        LocalDateTime fechaUltimaModificacion,
        int diasHabilesTranscurridos,
        boolean atrasado,
        /**
         * True si la deuda del pedido ya se emitió en finanzas. Un pedido
         * ENTREGADO con esto en false significa que la emisión falló (best-effort):
         * el trabajo se entregó pero NO se le facturó al odontólogo.
         */
        boolean comprobanteGenerado
) {
    public static PedidoResponse from(Pedido p, int diasLimiteAtraso) {
        LocalDateTime ref = (p.getEstado() == EstadoPedido.ENTREGADO || p.getEstado() == EstadoPedido.CANCELADO)
                ? (p.getFechaUltimaModificacion() != null ? p.getFechaUltimaModificacion() : LocalDateTime.now())
                : LocalDateTime.now();

        int dias = DiasHabiles.entre(p.getFechaCreacion(), ref);

        boolean estaAtrasado = (p.getEstado() != EstadoPedido.ENTREGADO
                             && p.getEstado() != EstadoPedido.CANCELADO)
                             && dias >= diasLimiteAtraso;

        return new PedidoResponse(
                p.getId(), p.getNroPedido(),
                p.getOdontologoId(), p.getOdontologoNombre(),
                p.getPaciente(),
                p.getCatalogoTrabajoId(), p.getTrabajo(),
                p.getTecnicoId(), p.getTecnicoNombre(),
                p.getFechaEntrega(), p.getEstado(), p.getPrioridad(),
                p.getPrecioAcordado(), p.getObservaciones(),
                p.getFechaEntregaReal(), p.getRetiradoPor(), p.getObservacionesEntrega(),
                p.getFechaCreacion(), p.getFechaUltimaModificacion(),
                dias, estaAtrasado, p.isComprobanteGenerado()
        );
    }

    public static PedidoResponse from(Pedido p) {
        return from(p, 6);
    }
}
