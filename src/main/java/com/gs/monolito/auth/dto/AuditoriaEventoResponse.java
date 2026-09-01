package com.gs.monolito.auth.dto;

import com.gs.monolito.auth.model.AuditoriaEvento;

public record AuditoriaEventoResponse(
    Long id,
    String timestamp,
    String usuario,
    String tipo,
    String accion,
    String entidad,
    String detalle
) {
    public static AuditoriaEventoResponse from(AuditoriaEvento e) {
        return new AuditoriaEventoResponse(
            e.getId(),
            e.getTimestamp().toString(),
            e.getUsuario(),
            e.getTipo(),
            e.getAccion(),
            e.getEntidad(),
            e.getDetalle() != null ? e.getDetalle() : ""
        );
    }
}
