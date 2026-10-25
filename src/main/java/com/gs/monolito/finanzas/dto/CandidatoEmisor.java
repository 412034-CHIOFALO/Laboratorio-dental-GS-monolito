package com.gs.monolito.finanzas.dto;

import java.math.BigDecimal;

/** Un odontólogo con deuda que podría ser quien pagó un comprobante (cuando el nombre del emisor no alcanza para estar seguro). */
public record CandidatoEmisor(Long odontologoId, String nombre, BigDecimal deuda) {}
