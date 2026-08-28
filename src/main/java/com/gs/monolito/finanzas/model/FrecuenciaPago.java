package com.gs.monolito.finanzas.model;

/**
 * Frecuencia con la que cobra un integrante del laboratorio.
 * Define el "ciclo" sobre el que se calcula el monto base.
 */
public enum FrecuenciaPago {
    DIARIO,     // cobra por día trabajado
    SEMANAL,    // una vez por semana
    QUINCENAL,  // cada 15 días
    MENSUAL     // una vez al mes
    ;

    /**
     * Cantidad de días que representa un ciclo completo de esta frecuencia —
     * se usa para prorratear el devengo diario (montoBase / diasDeCiclo()).
     * Días corridos fijos (no el largo real del mes), para que la tarifa diaria
     * de un empleado mensual no cambie de un mes a otro.
     */
    public int diasDeCiclo() {
        return switch (this) {
            case DIARIO -> 1;
            case SEMANAL -> 7;
            case QUINCENAL -> 15;
            case MENSUAL -> 30;
        };
    }
}
