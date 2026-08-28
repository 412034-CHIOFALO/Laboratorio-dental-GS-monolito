package com.gs.monolito.finanzas.dto;

import com.gs.monolito.finanzas.model.TipoCaja;
import com.gs.monolito.finanzas.model.TipoMovimientoCaja;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CajaMovimientoRequest(
    @NotNull TipoMovimientoCaja tipo,
    @NotNull TipoCaja tipoCaja,
    @NotBlank @Size(max = 200) String concepto,
    @NotNull @DecimalMin(value = "0.01", message = "El monto debe ser mayor a cero")
    @Digits(integer = 10, fraction = 2, message = "El monto excede el máximo permitido") BigDecimal monto,
    @Size(max = 50) String referencia,
    LocalDate fechaMovimiento,
    @Size(max = 50) String creadoPor
) {}
