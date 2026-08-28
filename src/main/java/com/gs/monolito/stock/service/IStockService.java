package com.gs.monolito.stock.service;

import com.gs.monolito.stock.dto.MaterialRequest;
import com.gs.monolito.stock.dto.MaterialResponse;
import com.gs.monolito.stock.dto.MovimientoRequest;

import java.util.List;

/**
 * Contrato de negocio para la gestión del inventario de materiales.
 * Las salidas de stock por pedidos son iniciadas por el módulo pedidos, que
 * llama a {@link #registrarMovimiento(MovimientoRequest)} con
 * {@code tipo = SALIDA} y el {@code pedidoId} correspondiente (antes vía
 * Feign; a partir de la Etapa 5, llamada directa in-process).
 */
public interface IStockService {

    List<MaterialResponse> listarActivos();

    List<MaterialResponse> listarBajoStock();

    MaterialResponse buscarPorId(Long id);

    MaterialResponse crear(MaterialRequest request);

    MaterialResponse actualizar(Long id, MaterialRequest request);

    MaterialResponse registrarMovimiento(MovimientoRequest request);

    void eliminar(Long id);
}
