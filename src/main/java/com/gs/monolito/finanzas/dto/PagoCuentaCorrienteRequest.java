package com.gs.monolito.finanzas.dto;

import com.gs.monolito.finanzas.model.MedioPago;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Pago manual a la cuenta corriente de un odontólogo. El monto se imputa a sus
 * deudas pendientes (más viejas primero), pudiendo ser parcial.
 */
public record PagoCuentaCorrienteRequest(
        @NotNull(message = "El monto es obligatorio")
        @Positive(message = "El monto debe ser mayor a cero")
        @Digits(integer = 10, fraction = 2, message = "El monto excede el máximo permitido")
        BigDecimal monto,

        @NotNull(message = "El medio de pago es obligatorio")
        MedioPago medio,

        LocalDate fecha,

        @Size(max = 255) String nota
) {}
