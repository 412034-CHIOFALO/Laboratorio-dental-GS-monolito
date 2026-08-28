package com.gs.monolito.finanzas.dto;

import java.math.BigDecimal;

/** Una línea de la distribución sugerida: cuánto le corresponde a un empleado. */
public record LineaDistribucionResponse(
    Long empleadoId,
    String empleadoNombre,
    BigDecimal monto
) {}
