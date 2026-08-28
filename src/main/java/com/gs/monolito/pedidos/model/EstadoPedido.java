package com.gs.monolito.pedidos.model;

/**
 * Estados por los que pasa un pedido: RECIBIDO → EN_PROCESO → CONTROL →
 * LISTO → ENTREGADO (o CANCELADO desde cualquier estado previo a la entrega).
 */
public enum EstadoPedido {
    RECIBIDO,
    EN_PROCESO,
    CONTROL,
    LISTO,
    ENTREGADO,
    CANCELADO;

    /**
     * true si este estado ya alcanzó o superó EN_PROCESO. El Kanban permite
     * arrastrar una tarjeta directamente a cualquier columna (no solo la
     * adyacente), así que un pedido puede saltar de RECIBIDO a CONTROL sin
     * pisar nunca el valor literal EN_PROCESO — por eso el gatillo de
     * descuento de stock usa este método en vez de una igualdad exacta.
     */
    public boolean alcanzoProduccion() {
        return this == EN_PROCESO || this == CONTROL || this == LISTO || this == ENTREGADO;
    }
}
