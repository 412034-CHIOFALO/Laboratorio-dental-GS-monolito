package com.gs.monolito.finanzas.model;

/**
 * Qué hacer cuando un pago supera lo que el laboratorio le debía al empleado.
 *  - DESCONTAR_PROXIMO: el excedente se descuenta del próximo ciclo
 *  - CUBRE_LAB:         el laboratorio absorbe la diferencia (queda saldado)
 *  - DEVUELVE_EMPLEADO: el empleado devuelve la diferencia al lab
 */
public enum ManejoSobrante {
    DESCONTAR_PROXIMO,
    CUBRE_LAB,
    DEVUELVE_EMPLEADO
}
