package com.gs.monolito.finanzas.dto;

import com.gs.monolito.finanzas.model.FrecuenciaPago;
import com.gs.monolito.finanzas.model.TipoDia;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** El calendario de un mes de un empleado, listo para dibujarlo, con su resumen. */
public record AsistenciaMesResponse(
        String mes,
        Long empleadoId,
        String empleadoNombre,
        FrecuenciaPago frecuencia,
        LocalDate fechaAlta,
        LocalDate fechaBaja,
        List<Dia> dias,
        Resumen resumen
) {
    /**
     * @param tipo       la marca del día, o null si es un día común
     * @param sePaga     si ese día se le paga (según la marca y cómo cobra el empleado)
     * @param enRelacion false si el día cae antes del alta o después de la baja
     */
    public record Dia(LocalDate fecha, TipoDia tipo, boolean sePaga, boolean enRelacion) {}

    /**
     * @param sueldoDelMes cuánto suma el mes completo según la asistencia cargada hoy (incluye los días que
     *                     todavía no pasaron, dados por trabajados salvo que estén marcados)
     */
    public record Resumen(
            int diasPagos,
            int diasNoPagos,
            int trabajados,
            int ausencias,
            int vacaciones,
            int feriados,
            int licencias,
            BigDecimal sueldoDelMes,
            int vacacionesAnuales,
            int vacacionesTomadasEnElAnio,
            int vacacionesDisponibles
    ) {}
}
