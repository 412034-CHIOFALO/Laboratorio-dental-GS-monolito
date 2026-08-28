package com.gs.monolito.pedidos.service;

import com.gs.monolito.pedidos.dto.OdontologoRequest;
import com.gs.monolito.pedidos.dto.OdontologoResponse;
import com.gs.monolito.pedidos.exception.ResourceNotFoundException;
import com.gs.monolito.pedidos.model.Odontologo;

import java.util.List;

/**
 * Contrato del servicio de negocio para la gestión de odontólogos clientes del laboratorio.
 */
public interface IOdontologoService {

    List<OdontologoResponse> listarActivos();

    /** Incluye también los desactivados — solo para el panel de gestión. */
    List<OdontologoResponse> listarTodos();

    List<OdontologoResponse> buscarPorNombre(String fragmento);

    OdontologoResponse buscarPorId(Long id);

    OdontologoResponse crear(OdontologoRequest request);

    OdontologoResponse actualizar(Long id, OdontologoRequest request);

    void desactivar(Long id);

    /**
     * Busca un odontólogo por nombre exacto (case-insensitive) y, si no existe,
     * lo crea. Patrón find-or-create usado por el flujo "Nuevo pedido".
     */
    Odontologo buscarOCrearPorNombre(String nombre);
}
