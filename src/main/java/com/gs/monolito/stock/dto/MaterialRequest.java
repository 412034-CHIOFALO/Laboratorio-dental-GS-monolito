package com.gs.monolito.stock.dto;

import com.gs.monolito.stock.model.CategoriaMaterial;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class MaterialRequest {

    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 150, message = "El nombre no puede superar los 150 caracteres")
    private String nombre;

    @Size(max = 1000)
    private String descripcion;

    @NotNull(message = "La categoría es obligatoria")
    private CategoriaMaterial categoria;

    @NotNull @PositiveOrZero
    private Double stockActual;

    @NotNull @PositiveOrZero
    private Double stockMinimo;

    @NotBlank(message = "La unidad de medida es obligatoria")
    @Size(max = 20, message = "La unidad de medida no puede superar los 20 caracteres")
    private String unidadMedida;

    @PositiveOrZero
    @Digits(integer = 10, fraction = 2, message = "El precio excede el máximo permitido")
    private BigDecimal precioUnitario;

    @Size(max = 100)
    private String proveedor;

    private Boolean descuentaStock = true;
}
