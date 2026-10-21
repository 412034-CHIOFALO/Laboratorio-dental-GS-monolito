package com.gs.monolito.finanzas.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** Baja de un empleado: {@code fechaBaja} es su último día de trabajo (si viene vacía, hoy). */
public record BajaEmpleadoRequest(
        LocalDate fechaBaja,
        @Size(max = 200, message = "El motivo no puede superar los 200 caracteres")
        String motivo
) {}
