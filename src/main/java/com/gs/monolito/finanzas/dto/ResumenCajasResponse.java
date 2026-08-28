package com.gs.monolito.finanzas.dto;

import java.math.BigDecimal;
import java.util.List;

public record ResumenCajasResponse(
    BigDecimal saldoFisica,
    BigDecimal saldoBancaria,
    BigDecimal saldoCompensacion,
    BigDecimal totalDeudaProveedores,
    BigDecimal totalSueldosPendientes,
    List<String> alertas,
    /** Triangulados incompletos que explican por qué saldoCompensacion no da $0. */
    List<DescuadreCompensacionResponse> descuadresCompensacion
) {}
