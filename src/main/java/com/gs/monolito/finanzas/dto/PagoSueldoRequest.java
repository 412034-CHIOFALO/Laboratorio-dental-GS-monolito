package com.gs.monolito.finanzas.dto;

import com.gs.monolito.finanzas.model.ManejoSobrante;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Pago manual cargado desde la app. */
@Data
public class PagoSueldoRequest {

    @NotNull(message = "El empleado es obligatorio")
    private Long usuarioId;

    @NotNull @Positive(message = "El monto debe ser mayor a cero")
    @Digits(integer = 10, fraction = 2, message = "El monto excede el máximo permitido")
    private BigDecimal monto;

    private ManejoSobrante manejoSobrante = ManejoSobrante.DESCONTAR_PROXIMO;

    private LocalDate fecha;

    @Size(max = 300)
    private String nota;
}
