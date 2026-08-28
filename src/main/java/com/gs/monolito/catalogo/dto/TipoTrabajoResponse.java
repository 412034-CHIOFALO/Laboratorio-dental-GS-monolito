package com.gs.monolito.catalogo.dto;

import com.gs.monolito.catalogo.model.Categoria;
import com.gs.monolito.catalogo.model.TipoTrabajo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record TipoTrabajoResponse(
        Long id,
        String nombre,
        String descripcion,
        BigDecimal precio,
        Categoria categoria,
        Integer tiempoEstimadoDias,
        String fotoUrl,
        boolean activo,
        List<IngredienteRecetaResponse> receta,
        LocalDateTime fechaCreacion,
        LocalDateTime fechaModificacion
) {
    public static TipoTrabajoResponse from(TipoTrabajo t) {
        return new TipoTrabajoResponse(
                t.getId(), t.getNombre(), t.getDescripcion(),
                t.getPrecio(), t.getCategoria(), t.getTiempoEstimadoDias(),
                t.getFotoUrl(), t.isActivo(),
                t.getReceta().stream().map(IngredienteRecetaResponse::from).toList(),
                t.getFechaCreacion(), t.getFechaModificacion()
        );
    }
}
