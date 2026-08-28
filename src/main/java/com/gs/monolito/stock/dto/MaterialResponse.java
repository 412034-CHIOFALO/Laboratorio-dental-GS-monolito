package com.gs.monolito.stock.dto;

import com.gs.monolito.stock.model.CategoriaMaterial;
import com.gs.monolito.stock.model.Material;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record MaterialResponse(
        Long id,
        String nombre,
        String descripcion,
        CategoriaMaterial categoria,
        Double stockActual,
        Double stockMinimo,
        String unidadMedida,
        BigDecimal precioUnitario,
        String proveedor,
        boolean activo,
        boolean descuentaStock,
        boolean bajoStock,
        LocalDateTime fechaModificacion
) {
    public static MaterialResponse from(Material m) {
        return new MaterialResponse(
                m.getId(), m.getNombre(), m.getDescripcion(),
                m.getCategoria(), m.getStockActual(), m.getStockMinimo(),
                m.getUnidadMedida(), m.getPrecioUnitario(), m.getProveedor(),
                m.isActivo(),
                m.isDescuentaStock(),
                m.getStockActual() <= m.getStockMinimo(),
                m.getFechaModificacion()
        );
    }
}
