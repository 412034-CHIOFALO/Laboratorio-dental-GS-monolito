package com.gs.monolito.finanzas.model;

/**
 * Medio por el que un odontólogo paga su cuenta corriente.
 * Determina a qué caja entra el dinero.
 */
public enum MedioPago {
    /** Efectivo → entra a la Caja Física. */
    EFECTIVO,
    /** Transferencia → entra a la Caja Bancaria. */
    TRANSFERENCIA
}
