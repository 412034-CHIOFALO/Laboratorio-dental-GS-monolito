package com.gs.monolito.pedidos.dto;

import com.gs.monolito.pedidos.model.SolicitudEntrante.RemitenteTipo;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Alta manual de un remitente autorizado (por ejemplo, la secretaria de un odontólogo, antes de que escriba). */
public record RemitenteRequest(
        @NotBlank(message = "Ingresá el mail") @Size(max = 150, message = "El mail no puede superar los 150 caracteres") String email,
        @NotNull(message = "Indicá quién es") RemitenteTipo tipo,
        Long odontologoId,
        @Size(max = 100, message = "La aclaración no puede superar los 100 caracteres") String aclaracion
) {}
