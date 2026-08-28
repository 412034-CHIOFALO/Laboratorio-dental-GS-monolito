package com.gs.monolito.catalogo.dto;

import com.gs.monolito.catalogo.model.IngredienteReceta;

import java.math.BigDecimal;

public record IngredienteRecetaResponse(
        Long id,
        Long materialId,
        String materialNombre,
        BigDecimal cantidad,
        String unidad,
        String notas
) {
    public static IngredienteRecetaResponse from(IngredienteReceta i) {
        return new IngredienteRecetaResponse(
                i.getId(),
                i.getMaterialId(),
                i.getMaterialNombre(),
                i.getCantidad(),
                i.getUnidad(),
                i.getNotas()
        );
    }
}
