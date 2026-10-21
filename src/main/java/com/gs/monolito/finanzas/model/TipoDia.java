package com.gs.monolito.finanzas.model;

/**
 * Qué pasó un día con un empleado, para liquidar su sueldo.
 *
 * <p>Un día SIN marca se interpreta según cómo cobra el empleado: quien tiene sueldo por ciclo
 * (semanal, quincenal, mensual) cobra todos los días salvo los marcados {@link #AUSENTE}; quien cobra
 * por día trabajado ({@link FrecuenciaPago#DIARIO}) solo cobra los días marcados con goce.</p>
 */
public enum TipoDia {
    /** Trabajó. Se paga. (Para quien cobra por día es la marca que lo habilita a cobrar.) */
    TRABAJADO(true),
    /** Faltó sin goce de sueldo: no se paga. */
    AUSENTE(false),
    /** Vacaciones: se pagan y descuentan del cupo anual. */
    VACACIONES(true),
    /** Feriado: se paga. */
    FERIADO(true),
    /** Licencia con goce (enfermedad, estudio, duelo...): se paga. */
    LICENCIA_PAGA(true);

    private final boolean pago;

    TipoDia(boolean pago) {
        this.pago = pago;
    }

    /** true si ese día se le paga al empleado. */
    public boolean isPago() {
        return pago;
    }
}
