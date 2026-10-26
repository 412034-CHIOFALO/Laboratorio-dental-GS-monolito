package com.gs.monolito.pedidos.dto;

import com.gs.monolito.pedidos.model.SolicitudEntrante.RemitenteTipo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Lo que decide una persona sobre un mail de un remitente sin autorizar: quién es ({@code tipo}), a qué odontólogo
 * representa (obligatorio salvo para OTRO) y si se recuerda la dirección para que los próximos mails entren directo.
 *
 * <p>Si el odontólogo todavía no está cargado, en vez de {@code odontologoId} se manda {@code nuevoOdontologo} y se lo
 * da de alta en el mismo paso (con el mail del remitente, si es él quien escribe).</p>
 */
public record AceptarRemitenteRequest(
        @NotNull(message = "Indicá quién es el remitente") RemitenteTipo tipo,
        Long odontologoId,
        @Size(max = 100, message = "La aclaración no puede superar los 100 caracteres") String aclaracion,
        Boolean recordar,
        @Valid NuevoOdontologo nuevoOdontologo
) {
    /** Los datos mínimos para dar de alta a un odontólogo desde la bandeja; el resto se completa después en su ficha. */
    public record NuevoOdontologo(
            @NotBlank(message = "El nombre del odontólogo es obligatorio")
            @Size(max = 150, message = "El nombre no puede superar los 150 caracteres") String nombre,
            @Size(max = 200, message = "La clínica no puede superar los 200 caracteres") String clinica,
            @Pattern(regexp = "^[0-9+()\\-\\s]{6,30}$|^$", message = "El teléfono solo puede contener números y los símbolos + - ( )") String telefono
    ) {}

    public boolean recordarDireccion() {
        return recordar == null || recordar;
    }
}
