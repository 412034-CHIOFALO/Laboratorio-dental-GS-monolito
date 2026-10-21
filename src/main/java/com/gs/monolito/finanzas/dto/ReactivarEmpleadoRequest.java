package com.gs.monolito.finanzas.dto;

import java.time.LocalDate;

/** Vuelta de un empleado dado de baja: {@code desde} es su primer día de trabajo (si viene vacía, hoy). */
public record ReactivarEmpleadoRequest(LocalDate desde) {}
