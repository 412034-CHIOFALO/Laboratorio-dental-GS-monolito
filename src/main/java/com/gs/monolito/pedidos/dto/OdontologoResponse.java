package com.gs.monolito.pedidos.dto;

import com.gs.monolito.pedidos.model.Odontologo;

import java.time.LocalDateTime;

public record OdontologoResponse(
        Long id,
        String nombre,
        String dni,
        String cuit,
        String telefono,
        String email,
        String matricula,
        String clinica,
        String direccion,
        Boolean activo,
        LocalDateTime fechaCreacion,
        LocalDateTime fechaModificacion,
        /** Fecha del último pedido de este odontólogo (null si nunca pidió). */
        LocalDateTime ultimoPedido,
        /** True si no tiene pedidos en los últimos N meses (inactivo por tiempo). */
        boolean inactivoPorTiempo
) {
    public static OdontologoResponse from(Odontologo o) {
        return from(o, null, false);
    }

    public static OdontologoResponse from(Odontologo o, LocalDateTime ultimoPedido, boolean inactivoPorTiempo) {
        return new OdontologoResponse(
                o.getId(),
                o.getNombre(),
                o.getDni(),
                o.getCuit(),
                o.getTelefono(),
                o.getEmail(),
                o.getMatricula(),
                o.getClinica(),
                o.getDireccion(),
                o.getActivo(),
                o.getFechaCreacion(),
                o.getFechaModificacion(),
                ultimoPedido,
                inactivoPorTiempo
        );
    }
}
