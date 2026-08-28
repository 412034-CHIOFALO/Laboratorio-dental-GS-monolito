package com.gs.monolito.finanzas.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class ComprobanteRequest {

    @NotNull
    private Long pedidoId;

    @NotBlank
    @Size(max = 30)
    private String nroPedido;

    @NotNull
    private Long odontologoId;

    @NotBlank
    @Size(max = 150)
    private String odontologoNombre;

    @NotBlank
    @Size(max = 200)
    private String trabajo;

    @NotNull @Positive
    @Digits(integer = 10, fraction = 2, message = "El monto excede el máximo permitido")
    private BigDecimal monto;

    @NotNull
    private LocalDate fechaEmision;

    private LocalDate fechaVencimiento;

    @Size(max = 255)
    private String observaciones;
}
