package com.gs.monolito.pedidos.service;

import com.gs.monolito.pedidos.dto.EntregaRequest;
import com.gs.monolito.pedidos.dto.PedidoRequest;
import com.gs.monolito.pedidos.dto.PedidoResponse;
import com.gs.monolito.pedidos.exception.ResourceNotFoundException;
import com.gs.monolito.pedidos.model.EstadoPedido;

import java.util.List;

/**
 * Contrato del servicio de negocio para la gestión de pedidos del laboratorio dental.
 */
public interface IPedidoService {

    List<PedidoResponse> listarTodos();

    /** Pedidos en producción activa (estados distintos de ENTREGADO/CANCELADO — ver implementación). */
    List<PedidoResponse> listarActivos();

    List<PedidoResponse> listarPorEstado(EstadoPedido estado);

    PedidoResponse buscarPorId(Long id);

    /**
     * Registra un nuevo pedido: genera nroPedido, aplica estado inicial
     * RECIBIDO y resuelve el odontólogo (find-or-create por nombre, o por id
     * si vino explícito).
     */
    PedidoResponse crear(PedidoRequest request);

    PedidoResponse actualizar(Long id, PedidoRequest request);

    /**
     * Cambia el estado de un pedido (operación del tablero kanban). Al
     * alcanzar EN_PROCESO dispara el consumo automático de stock (una sola vez).
     */
    PedidoResponse actualizarEstado(Long id, EstadoPedido nuevoEstado);

    /** Transición LISTO → ENTREGADO. Registra fecha real, quién retiró y emite el comprobante. */
    PedidoResponse marcarEntregado(Long id, EntregaRequest request);

    void eliminar(Long id);

    List<PedidoResponse> listarAtrasados();
}
