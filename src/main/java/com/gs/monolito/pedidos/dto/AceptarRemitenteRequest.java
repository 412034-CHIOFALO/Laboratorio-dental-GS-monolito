package com.gs.monolito.pedidos.dto;

import com.gs.monolito.pedidos.model.SolicitudEntrante.RemitenteTipo;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Lo que decide una persona sobre un mail de un remitente sin autorizar: quién es ({@code tipo}), a qué odontólogo
 * representa (obligatorio salvo para OTRO) y si se recuerda la dirección para que los próximos mails entren directo.
 */
public record AceptarRemitenteRequest(
        @NotNull(message = "Indicá quién es el remitente") RemitenteTipo tipo,
        Long odontologoId,
        @Size(max = 100, message = "La aclaración no puede superar los 100 caracteres") String aclaracion,
        Boolean recordar
) {
    public boolean recordarDireccion() {
        return recordar == null || recordar;
    }
}
