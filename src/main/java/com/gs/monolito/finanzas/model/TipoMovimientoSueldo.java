package com.gs.monolito.finanzas.model;

/** Por qué cambió el saldo de un empleado (ver {@link MovimientoSueldo}). */
public enum TipoMovimientoSueldo {
    /** Sueldo devengado por los días que se cerraron. */
    DEVENGO,
    /** Corrección porque se marcó (o se desmarcó) una ausencia, vacaciones, etc. en un día ya devengado. */
    AJUSTE_ASISTENCIA,
    /** Alguien pisó el saldo a mano, con su motivo. */
    AJUSTE_MANUAL,
    /** Se le pagó al empleado (resta del saldo). */
    PAGO
}
