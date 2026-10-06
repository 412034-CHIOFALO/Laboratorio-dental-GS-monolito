package com.gs.monolito.finanzas.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** IDs de mensajes de WhatsApp que el bot quiere saber si el sistema ya registró. */
public record MensajesConocidosRequest(
        @NotNull @Size(max = 500) List<@Size(max = 128) String> ids
) {}
