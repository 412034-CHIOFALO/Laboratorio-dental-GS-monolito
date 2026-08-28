package com.gs.monolito.finanzas.service;

import com.gs.monolito.finanzas.dto.DeudaProveedorRequest;
import com.gs.monolito.finanzas.dto.DeudaProveedorResponse;
import com.gs.monolito.finanzas.model.TipoCaja;

import java.util.List;

/**
 * Servicio de gestión de deudas del laboratorio con sus proveedores de materiales.
 */
public interface IDeudaProveedorService {

    List<DeudaProveedorResponse> listarPorProveedor(Long proveedorId);

    List<DeudaProveedorResponse> listarPendientes();

    /** Deudas ya pagadas (manualmente desde el panel), más recientes primero. */
    List<DeudaProveedorResponse> listarPagadas();

    DeudaProveedorResponse buscarPorId(Long id);

    DeudaProveedorResponse registrar(DeudaProveedorRequest request);

    /**
     * Marca una deuda como pagada, registrando la fecha de pago (hoy) y el
     * egreso correspondiente en la caja indicada.
     */
    DeudaProveedorResponse pagar(Long id, TipoCaja caja);
}
