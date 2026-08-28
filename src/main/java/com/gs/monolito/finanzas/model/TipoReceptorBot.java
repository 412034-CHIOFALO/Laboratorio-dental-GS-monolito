package com.gs.monolito.finanzas.model;

/**
 * A quién identificó el bot como receptor del comprobante.
 *  - EMPLEADO    → pago de sueldo a un integrante del lab.
 *  - PROVEEDOR   → pago a un proveedor (insumos / triangulado).
 *  - DESCONOCIDO → no se pudo resolver ni empleado ni proveedor.
 */
public enum TipoReceptorBot {
    EMPLEADO,
    PROVEEDOR,
    DESCONOCIDO
}
