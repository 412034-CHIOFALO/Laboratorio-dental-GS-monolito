package com.gs.monolito.pedidos.dto;

import com.gs.monolito.pedidos.model.RemitenteAutorizado;
import com.gs.monolito.pedidos.model.SolicitudEntrante.RemitenteTipo;

import java.time.LocalDateTime;

/** Una dirección que el laboratorio autorizó o bloqueó. */
public record RemitenteResponse(
        Long id,
        String email,
        RemitenteTipo tipo,
        Long odontologoId,
        String etiqueta,
        RemitenteAutorizado.Estado estado,
        String creadoPor,
        LocalDateTime fechaCreacion
) {
    public static RemitenteResponse from(RemitenteAutorizado r) {
        return new RemitenteResponse(r.getId(), r.getEmail(), r.getTipo(), r.getOdontologoId(), r.getEtiqueta(),
                r.getEstado(), r.getCreadoPor(), r.getFechaCreacion());
    }
}
