package com.gs.monolito.finanzas.dto;

import com.gs.monolito.finanzas.model.ManejoSobrante;
import com.gs.monolito.finanzas.model.OrigenPago;
import com.gs.monolito.finanzas.model.PagoSueldo;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Item del histórico de pagos de sueldo. */
public record PagoSueldoResponse(
        Long id,
        Long empleadoId,
        String empleadoNombre,
        BigDecimal monto,
        LocalDate fecha,
        OrigenPago origen,
        ManejoSobrante manejoSobrante,
        BigDecimal montoExcedente,
        String nota,
        String cargadoPorNombre,
        String emisor,
        String comprobanteUrl,
        String grupoOrigen
) {
    public static PagoSueldoResponse from(PagoSueldo p) {
        return new PagoSueldoResponse(
                p.getId(),
                p.getEmpleadoId(),
                p.getEmpleadoNombre(),
                p.getMonto(),
                p.getFecha(),
                p.getOrigen(),
                p.getManejoSobrante(),
                p.getMontoExcedente(),
                p.getNota(),
                p.getCargadoPorNombre(),
                p.getEmisor(),
                p.getComprobanteUrl(),
                p.getGrupoOrigen()
        );
    }
}
