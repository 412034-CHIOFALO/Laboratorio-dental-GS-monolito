package com.gs.monolito.finanzas.model;

public enum EstadoPago {
    /** Emitido, sin ningún pago imputado. */
    PENDIENTE,
    /** Tiene pagos parciales pero todavía resta saldo. */
    PARCIAL,
    /** Saldado por completo. */
    COBRADO,
    /** Superó la fecha de vencimiento sin saldarse. */
    VENCIDO
}
