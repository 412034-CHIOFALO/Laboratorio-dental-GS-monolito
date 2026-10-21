package com.gs.monolito.finanzas.dto;

import com.gs.monolito.finanzas.model.ConfiguracionSueldo;
import com.gs.monolito.finanzas.model.FrecuenciaPago;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Vista de la configuración de sueldo de un empleado. */
public record EmpleadoSueldoResponse(
        Long usuarioId,
        String nombre,
        String rol,
        String telefono,
        boolean activo,
        FrecuenciaPago frecuencia,
        BigDecimal montoBase,
        BigDecimal saldoDevengado,
        BigDecimal saldoSobrante,
        LocalDate ultimoPago,
        LocalDate fechaAlta,
        LocalDate fechaBaja,
        int diasVacacionesAnuales,
        /** Último día ya devengado (el saldo incluye hasta ese día); null si todavía no se cerró ninguno. */
        LocalDate devengadoHasta
) {
    public static EmpleadoSueldoResponse from(ConfiguracionSueldo c) {
        return new EmpleadoSueldoResponse(
                c.getEmpleadoId(),
                c.getEmpleadoNombre(),
                c.getRol(),
                c.getTelefono(),
                c.isActivo(),
                c.getFrecuencia(),
                c.getMontoBase(),
                c.getSaldoDevengado(),
                c.getSaldoSobrante(),
                c.getUltimoPago(),
                c.getFechaAlta(),
                c.getFechaBaja(),
                c.getDiasVacacionesAnuales(),
                c.getUltimoDevengoCalculado() == null ? null : c.getUltimoDevengoCalculado().minusDays(1)
        );
    }
}
