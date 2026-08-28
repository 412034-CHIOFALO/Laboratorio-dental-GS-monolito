package com.gs.monolito.finanzas.model;

/** Medio de pago utilizado en el comprobante/registro del bot. */
public enum FuentePago {
    /** Transferencia bancaria o billetera virtual (flujo normal del bot). */
    TRANSFERENCIA,
    /** Pago en efectivo declarado en el grupo — requiere confirmación manual. */
    EFECTIVO
}
