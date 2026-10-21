package com.gs.monolito.finanzas.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Corrección manual del saldo devengado. El motivo es obligatorio: queda en el libro y en la auditoría. */
public record AjusteDevengadoRequest(
        @NotNull(message = "El campo 'devengado' es obligatorio")
        @PositiveOrZero(message = "El devengado no puede ser negativo")
        BigDecimal devengado,
        @NotBlank(message = "Explicá el motivo del ajuste")
        @Size(min = 5, max = 250, message = "El motivo debe tener entre 5 y 250 caracteres")
        String motivo
) {}
