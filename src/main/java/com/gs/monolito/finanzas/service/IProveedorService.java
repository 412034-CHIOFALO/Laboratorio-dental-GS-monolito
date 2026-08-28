package com.gs.monolito.finanzas.service;

import com.gs.monolito.finanzas.dto.ProveedorRequest;
import com.gs.monolito.finanzas.dto.ProveedorResponse;

import java.util.List;

/**
 * Servicio de ABM de proveedores de materiales dentales del laboratorio.
 * Baja lógica ({@code activo = false}) para preservar el historial de
 * deudas y pagos.
 */
public interface IProveedorService {

    List<ProveedorResponse> listarActivos();

    ProveedorResponse buscarPorId(Long id);

    ProveedorResponse crear(ProveedorRequest request);

    ProveedorResponse actualizar(Long id, ProveedorRequest request);

    void desactivar(Long id);
}
