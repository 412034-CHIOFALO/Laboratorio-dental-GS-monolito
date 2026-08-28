package com.gs.monolito.finanzas.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Vista agregada de la cuenta corriente de UN odontólogo.
 * Devuelta como item del ranking de morosos: GET /api/finanzas/cuentas-corrientes
 *
 * Calculada a partir de comprobantes PENDIENTES agrupados por odontólogo.
 */
public record CuentaCorrienteOdontologoResponse(
        Long odontologoId,
        String odontologoNombre,
        BigDecimal totalDeuda,
        long comprobantesPendientes,
        /** Fecha del comprobante más viejo sin pagar. */
        LocalDate fechaMasAntigua,
        /** Días desde la fecha del comprobante más viejo hasta hoy. */
        long diasSinPagar,
        /** Clasificación visual sugerida (usable en UI sin lógica adicional). */
        Severidad severidad
) {
    public enum Severidad {
        /** $0 deuda — no debería aparecer en el ranking de morosos. */
        AL_DIA,
        /** Hasta $50.000 — deuda chica reciente. */
        BAJA,
        /** $50k-$200k o > 30 días sin pagar. */
        MEDIA,
        /** > $200k o > 60 días sin pagar. */
        ALTA,
        /** > $500k o > 90 días sin pagar. */
        CRITICA
    }
}
