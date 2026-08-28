package com.gs.monolito.catalogo.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class IngredienteRecetaRequest {

    @NotNull(message = "El material es obligatorio")
    private Long materialId;

    @NotBlank(message = "El nombre del material es obligatorio")
    @Size(max = 200)
    private String materialNombre;

    @NotNull(message = "La cantidad es obligatoria")
    @Positive(message = "La cantidad debe ser mayor a 0")
    @Digits(integer = 9, fraction = 3, message = "La cantidad excede el máximo permitido")
    private BigDecimal cantidad;

    @Size(max = 30)
    private String unidad;

    @Size(max = 500)
    private String notas;
}
