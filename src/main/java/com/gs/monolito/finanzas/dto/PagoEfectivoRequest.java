package com.gs.monolito.finanzas.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Request para registrar un pago en efectivo declarado en el grupo de WhatsApp.
 * El registro queda en estado PENDIENTE hasta que el administrativo lo confirme
 * o rechace desde el sistema.
 */
@Data
public class PagoEfectivoRequest {

    @NotBlank(message = "El nombre del receptor es obligatorio")
    @Size(max = 150)
    private String receptorNombre;

    @NotNull(message = "El monto es obligatorio")
    @Positive(message = "El monto debe ser mayor a cero")
    @Digits(integer = 10, fraction = 2, message = "El monto excede el máximo permitido")
    private BigDecimal monto;

    @Size(max = 200)
    private String emisor;

    @Size(max = 150)
    private String cargadoPorNombre;
    @Size(max = 30)
    private String cargadoPorTelefono;
    @Size(max = 100)
    private String grupoOrigen;
}
