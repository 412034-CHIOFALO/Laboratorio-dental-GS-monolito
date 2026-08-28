package com.gs.monolito.catalogo.dto;

import com.gs.monolito.catalogo.model.Categoria;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class TipoTrabajoRequest {

    @NotBlank(message = "El nombre del trabajo es obligatorio")
    @Size(max = 150, message = "El nombre no puede superar los 150 caracteres")
    private String nombre;

    @Size(max = 1000, message = "La descripción no puede superar los 1000 caracteres")
    private String descripcion;

    @PositiveOrZero(message = "El precio no puede ser negativo")
    @Digits(integer = 10, fraction = 2, message = "El precio excede el máximo permitido")
    private BigDecimal precio;

    @NotNull(message = "La categoría es obligatoria")
    private Categoria categoria;

    @PositiveOrZero
    private Integer tiempoEstimadoDias = 0;

    @Size(max = 500)
    private String fotoUrl;

    @Valid
    private List<IngredienteRecetaRequest> receta = new ArrayList<>();
}
