package com.gs.monolito.stock.model;

/**
 * Tipos de movimiento que pueden afectar el stock de un {@link Material}.
 * ENTRADA: stockActual += cantidad. SALIDA: stockActual -= cantidad.
 * AJUSTE: stockActual = cantidad (reemplazo absoluto).
 */
public enum TipoMovimiento {
    ENTRADA, SALIDA, AJUSTE
}
