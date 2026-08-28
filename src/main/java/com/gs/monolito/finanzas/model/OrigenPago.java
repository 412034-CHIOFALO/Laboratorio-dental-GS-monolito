package com.gs.monolito.finanzas.model;

/**
 * De dónde se originó el registro de un pago de sueldo.
 *  - MANUAL:       lo cargó un usuario desde la app
 *  - BOT_WHATSAPP: lo detectó el bot a partir de un comprobante en un grupo
 */
public enum OrigenPago {
    MANUAL,
    BOT_WHATSAPP
}
