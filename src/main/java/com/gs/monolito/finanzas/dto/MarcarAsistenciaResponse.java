package com.gs.monolito.finanzas.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resultado de marcar días: cuántos cambiaron de verdad, cuánto se corrigió el saldo (negativo si se descontó)
 * por los días que ya estaban devengados, cómo quedó el saldo y avisos para quien carga (ej. se pasó del cupo
 * de vacaciones).
 */
public record MarcarAsistenciaResponse(
        int diasModificados,
        BigDecimal ajuste,
        BigDecimal saldoDevengado,
        List<String> advertencias
) {}
