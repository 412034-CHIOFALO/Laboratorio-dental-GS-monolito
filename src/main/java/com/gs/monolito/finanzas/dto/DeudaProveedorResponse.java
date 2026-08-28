package com.gs.monolito.finanzas.dto;

import com.gs.monolito.finanzas.model.DeudaProveedor;
import com.gs.monolito.finanzas.model.EstadoDeuda;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DeudaProveedorResponse(
    Long id,
    Long proveedorId,
    String proveedorNombre,
    String descripcion,
    BigDecimal monto,
    BigDecimal montoPagado,
    BigDecimal saldoPendiente,
    EstadoDeuda estado,
    LocalDate fechaVencimiento,
    LocalDate fechaPago,
    String nroFacturaProveedor,
    String observaciones
) {
    public static DeudaProveedorResponse from(DeudaProveedor d) {
        return new DeudaProveedorResponse(
            d.getId(),
            d.getProveedor().getId(),
            d.getProveedor().getNombre(),
            d.getDescripcion(),
            d.getMonto(),
            d.getMontoPagado(),
            d.getSaldoPendiente(),
            d.getEstado(),
            d.getFechaVencimiento(),
            d.getFechaPago(),
            d.getNroFacturaProveedor(),
            d.getObservaciones()
        );
    }
}
