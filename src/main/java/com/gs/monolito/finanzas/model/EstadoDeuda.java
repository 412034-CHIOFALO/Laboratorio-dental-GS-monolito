package com.gs.monolito.finanzas.model;

public enum EstadoDeuda {
    /** Sin ningún pago imputado. */
    PENDIENTE,
    /** Tiene pagos parciales pero todavía resta saldo. */
    PARCIAL,
    /** Saldada por completo. */
    PAGADO
}
