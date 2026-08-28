package com.gs.monolito.finanzas.dto;

import com.gs.monolito.finanzas.model.MedioPago;
import com.gs.monolito.finanzas.model.PagoCuentaCorriente;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Resultado de registrar un pago a la cuenta corriente: el pago en sí más el
 * efecto (cuánto se imputó, cuántos comprobantes se afectaron y el saldo que le
 * queda al odontólogo).
 */
public record PagoCuentaCorrienteResponse(
        Long id,
        Long odontologoId,
        String odontologoNombre,
        BigDecimal monto,
        BigDecimal montoImputado,
        MedioPago medio,
        LocalDate fecha,
        String nota,
        int comprobantesAfectados,
        BigDecimal saldoRestante,
        String mensaje
) {

    public static PagoCuentaCorrienteResponse from(PagoCuentaCorriente p) {
        return new PagoCuentaCorrienteResponse(
                p.getId(), p.getOdontologoId(), p.getOdontologoNombre(),
                p.getMonto(), p.getMontoImputado(), p.getMedio(),
                p.getFecha(), p.getNota(), 0, null, null
        );
    }
}
