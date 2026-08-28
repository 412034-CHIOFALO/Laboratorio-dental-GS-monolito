package com.gs.monolito.finanzas.dto;

import com.gs.monolito.finanzas.model.TipoCaja;

import java.math.BigDecimal;
import java.util.List;

/**
 * Distribución sugerida de un cobro por el algoritmo de cascada: primero cubre
 * el saldo devengado pendiente de los empleados activos (en orden alfabético,
 * el que sigue solo recibe si sobra después del anterior), y lo que sobra
 * ({@code remanente}) queda propuesto para {@code cajaRemanente}.
 */
public record DistribucionCascadaResponse(
    List<LineaDistribucionResponse> empleados,
    BigDecimal remanente,
    TipoCaja cajaRemanente
) {}
