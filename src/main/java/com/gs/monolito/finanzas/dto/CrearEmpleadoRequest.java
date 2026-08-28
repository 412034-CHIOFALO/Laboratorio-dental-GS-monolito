package com.gs.monolito.finanzas.dto;

import com.gs.monolito.finanzas.model.FrecuenciaPago;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Alta manual (o disparada por el módulo auth al activar una cuenta) de un
 * integrante del laboratorio en el módulo de sueldos. Finanzas mantiene su
 * propia tabla de empleados (denormalizada de auth) — a partir de la
 * fusión en el monolito, auth invoca directo al service correspondiente
 * (antes vía Feign) usando este mismo DTO.
 */
@Data
public class CrearEmpleadoRequest {

    @NotNull(message = "El ID de usuario (auth) es obligatorio")
    private Long usuarioId;

    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 150, message = "El nombre no puede superar los 150 caracteres")
    private String nombre;

    @Size(max = 30, message = "El rol no puede superar los 30 caracteres")
    private String rol;

    @Size(max = 30, message = "El teléfono no puede superar los 30 caracteres")
    private String telefono;

    @NotNull(message = "La frecuencia es obligatoria")
    private FrecuenciaPago frecuencia;

    @NotNull(message = "El monto base es obligatorio")
    @PositiveOrZero(message = "El monto base no puede ser negativo")
    @Digits(integer = 10, fraction = 2, message = "El monto base excede el máximo permitido")
    private BigDecimal montoBase;
}
