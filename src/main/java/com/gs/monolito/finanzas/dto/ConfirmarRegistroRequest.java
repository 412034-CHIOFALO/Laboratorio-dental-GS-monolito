package com.gs.monolito.finanzas.dto;

import lombok.Data;

/**
 * Lo que decide quien aprueba un comprobante pendiente cuando el sistema no pudo saber solo QUIÉN pagó.
 * Sin ninguno de los dos campos, el sistema decide solo (y si no está seguro, pide que se elija).
 */
@Data
public class ConfirmarRegistroRequest {

    /** El odontólogo que pagó (uno de los candidatos que ofreció el sistema). */
    private Long odontologoId;

    /** true = no fue un odontólogo: lo pagó el laboratorio (pago normal, sin descontar la deuda de nadie). */
    private Boolean sinOdontologo;
}
