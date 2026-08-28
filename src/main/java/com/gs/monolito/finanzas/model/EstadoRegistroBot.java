package com.gs.monolito.finanzas.model;

/**
 * Resultado del procesamiento de un comprobante por el bot.
 *  - REGISTRADO → se aplicó el pago (sueldo o proveedor).
 *  - RECHAZADO  → no se pudo resolver al receptor.
 *  - DUPLICADO  → el nro de operación ya había sido registrado.
 */
public enum EstadoRegistroBot {
    /** Pago aplicado exitosamente (sueldo o proveedor). */
    REGISTRADO,
    /** Receptor no identificado o error en la aplicación. */
    RECHAZADO,
    /** Número de operación ya procesado antes. */
    DUPLICADO,
    /** Efectivo declarado en el grupo — pendiente de confirmación por el administrativo. */
    PENDIENTE
}
