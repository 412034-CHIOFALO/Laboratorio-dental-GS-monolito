package com.gs.monolito.finanzas.dto;

import com.gs.monolito.finanzas.model.FrecuenciaPago;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ConfigSueldoRequest {

    @NotNull(message = "La frecuencia es obligatoria")
    private FrecuenciaPago frecuencia;

    @NotNull(message = "El monto base es obligatorio")
    @PositiveOrZero(message = "El monto base no puede ser negativo")
    @Digits(integer = 10, fraction = 2, message = "El monto base excede el máximo permitido")
    private BigDecimal montoBase;

    /** Opcional: cupo anual de vacaciones. Si no viene, no se toca. */
    @Min(value = 0, message = "Los días de vacaciones no pueden ser negativos")
    @Max(value = 60, message = "Los días de vacaciones no pueden superar 60")
    private Integer diasVacacionesAnuales;
}
