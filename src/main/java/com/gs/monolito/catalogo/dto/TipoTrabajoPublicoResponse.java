package com.gs.monolito.catalogo.dto;

import com.gs.monolito.catalogo.model.Categoria;
import com.gs.monolito.catalogo.model.TipoTrabajo;

import java.math.BigDecimal;

/**
 * Espejo de {@link TipoTrabajoResponse} para el catálogo PÚBLICO (/api/publico/catalogo)
 * — a propósito sin el campo {@code receta}: qué materiales de stock usa cada
 * trabajo y en qué cantidad es información de costos interna, nunca sale acá.
 */
public record TipoTrabajoPublicoResponse(
        Long id,
        String nombre,
        String descripcion,
        BigDecimal precio,
        Categoria categoria,
        Integer tiempoEstimadoDias,
        String fotoUrl
) {
    public static TipoTrabajoPublicoResponse from(TipoTrabajo t) {
        return new TipoTrabajoPublicoResponse(
                t.getId(), t.getNombre(), t.getDescripcion(),
                t.getPrecio(), t.getCategoria(), t.getTiempoEstimadoDias(),
                t.getFotoUrl()
        );
    }
}
