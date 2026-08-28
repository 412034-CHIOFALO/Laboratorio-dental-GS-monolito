package com.gs.monolito.finanzas.dto;

import com.gs.monolito.finanzas.model.Comprobante;
import com.gs.monolito.finanzas.model.EstadoPago;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ComprobanteResponse(
        Long id,
        String nroComprobante,
        Long pedidoId,
        String nroPedido,
        Long odontologoId,
        String odontologoNombre,
        String trabajo,
        BigDecimal monto,
        BigDecimal montoPagado,
        BigDecimal saldoPendiente,
        EstadoPago estadoPago,
        LocalDate fechaEmision,
        LocalDate fechaVencimiento,
        LocalDate fechaCobro,
        String observaciones
) {
    public static ComprobanteResponse from(Comprobante c) {
        return new ComprobanteResponse(
                c.getId(), c.getNroComprobante(),
                c.getPedidoId(), c.getNroPedido(),
                c.getOdontologoId(), c.getOdontologoNombre(),
                c.getTrabajo(), c.getMonto(),
                c.getMontoPagado(), c.getSaldoPendiente(), c.getEstadoPago(),
                c.getFechaEmision(), c.getFechaVencimiento(),
                c.getFechaCobro(), c.getObservaciones()
        );
    }
}
