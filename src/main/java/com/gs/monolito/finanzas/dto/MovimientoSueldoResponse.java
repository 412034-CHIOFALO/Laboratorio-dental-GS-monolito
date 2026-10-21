package com.gs.monolito.finanzas.dto;

import com.gs.monolito.finanzas.model.MovimientoSueldo;
import com.gs.monolito.finanzas.model.TipoMovimientoSueldo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Una línea del libro de sueldos de un empleado. */
public record MovimientoSueldoResponse(
        Long id,
        LocalDate fecha,
        TipoMovimientoSueldo tipo,
        BigDecimal monto,
        BigDecimal saldoDevengado,
        BigDecimal saldoSobrante,
        String detalle,
        String usuario,
        LocalDateTime creadoEn
) {
    public static MovimientoSueldoResponse from(MovimientoSueldo m) {
        return new MovimientoSueldoResponse(m.getId(), m.getFecha(), m.getTipo(), m.getMonto(),
                m.getSaldoDevengado(), m.getSaldoSobrante(), m.getDetalle(), m.getUsuario(), m.getFechaCreacion());
    }
}
