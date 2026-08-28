package com.gs.monolito.pedidos.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Cálculo de días hábiles (lunes a viernes) entre dos fechas. No contempla
 * feriados nacionales/provinciales en esta versión.
 */
public final class DiasHabiles {

    private DiasHabiles() { }

    /**
     * Cuenta días hábiles (Lun-Vie) entre dos fechas. La fecha de inicio
     * NO se cuenta; la de fin SÍ si cae en día hábil.
     */
    public static int entre(LocalDate inicio, LocalDate fin) {
        if (inicio == null || fin == null) return 0;
        if (!fin.isAfter(inicio)) return 0;

        int diasHabiles = 0;
        LocalDate cursor = inicio.plusDays(1);
        while (!cursor.isAfter(fin)) {
            DayOfWeek dow = cursor.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                diasHabiles++;
            }
            cursor = cursor.plusDays(1);
        }
        return diasHabiles;
    }

    public static int entre(LocalDateTime inicio, LocalDateTime fin) {
        if (inicio == null || fin == null) return 0;
        return entre(inicio.toLocalDate(), fin.toLocalDate());
    }

    public static int desdeHasta(LocalDateTime inicio) {
        if (inicio == null) return 0;
        return entre(inicio.toLocalDate(), LocalDate.now());
    }
}
