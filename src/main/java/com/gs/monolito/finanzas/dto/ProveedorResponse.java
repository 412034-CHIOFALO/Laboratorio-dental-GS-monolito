package com.gs.monolito.finanzas.dto;

import com.gs.monolito.finanzas.model.Proveedor;

import java.math.BigDecimal;

public record ProveedorResponse(
    Long id,
    String nombre,
    String cuit,
    String email,
    String telefono,
    String direccion,
    boolean activo,
    BigDecimal deudaPendiente
) {
    public static ProveedorResponse from(Proveedor p, BigDecimal deuda) {
        return new ProveedorResponse(
            p.getId(), p.getNombre(), p.getCuit(), p.getEmail(),
            p.getTelefono(), p.getDireccion(), p.isActivo(), deuda
        );
    }

    public static ProveedorResponse from(Proveedor p) {
        return new ProveedorResponse(
            p.getId(), p.getNombre(), p.getCuit(), p.getEmail(),
            p.getTelefono(), p.getDireccion(), p.isActivo(), BigDecimal.ZERO
        );
    }
}
