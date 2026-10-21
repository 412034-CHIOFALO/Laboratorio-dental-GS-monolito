package com.gs.monolito.finanzas.dto;

import com.gs.monolito.finanzas.model.TipoDia;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * Marca varios días del calendario de un empleado de una vez (los que se eligieron con el selector de fechas).
 * {@code tipo = null} borra la marca: el día vuelve a ser un día común.
 */
public record MarcarAsistenciaRequest(
        @NotEmpty(message = "Elegí al menos un día")
        @Size(max = 400, message = "No se pueden marcar más de 400 días de una vez")
        List<LocalDate> fechas,
        TipoDia tipo,
        @Size(max = 200, message = "La nota no puede superar los 200 caracteres")
        String nota
) {}
