package com.gs.monolito.catalogo.service;

import com.gs.monolito.catalogo.dto.TipoTrabajoRequest;
import com.gs.monolito.catalogo.dto.TipoTrabajoResponse;
import com.gs.monolito.catalogo.model.Categoria;

import java.util.List;

/**
 * Contrato de la capa de servicio para la gestión del catálogo de tipos de trabajo dental.
 */
public interface ITipoTrabajoService {

    List<TipoTrabajoResponse> listarActivos();

    List<TipoTrabajoResponse> listarPorCategoria(Categoria categoria);

    List<TipoTrabajoResponse> buscarPorNombre(String nombre);

    TipoTrabajoResponse buscarPorId(Long id);

    TipoTrabajoResponse crear(TipoTrabajoRequest request);

    TipoTrabajoResponse actualizar(Long id, TipoTrabajoRequest request);

    void eliminar(Long id);
}
