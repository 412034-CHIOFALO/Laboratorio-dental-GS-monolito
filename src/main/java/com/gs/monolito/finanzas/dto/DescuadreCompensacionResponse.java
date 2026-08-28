package com.gs.monolito.finanzas.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Un triangulado incompleto en la Caja Compensación: un {@code referencia}
 * (mismo idOperacion del triangulado) cuya suma de ingresos/egresos no da
 * cero, es decir, le falta la mitad del par ingreso/egreso.
 */
public record DescuadreCompensacionResponse(
    String referencia,
    BigDecimal monto,
    String concepto,
    LocalDate fecha
) {}
