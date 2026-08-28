package com.gs.monolito.finanzas.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Pago triangulado a proveedor cargado manualmente desde el panel: un
 * odontólogo paga directamente a un proveedor una deuda del laboratorio, en
 * vez de pagarle al laboratorio. Mismo caso que ya resuelve el bot cuando lo
 * detecta en un comprobante de WhatsApp, pero disparado a mano.
 */
public record PagoTrianguladoProveedorRequest(
        @NotNull(message = "El proveedor es obligatorio") @Positive Long proveedorId,
        @NotNull(message = "El monto es obligatorio") @Positive BigDecimal monto,
        String nota
) {}
