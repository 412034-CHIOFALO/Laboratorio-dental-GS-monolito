package com.gs.monolito.stock.dto;

import com.gs.monolito.stock.model.TipoMovimiento;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MovimientoRequest {

    @NotNull
    private Long materialId;

    /**
     * Nombre del material, opcional. Si viene informado y existe un material con
     * ese nombre exacto, se usa para resolver el material en vez de materialId —
     * así un id mal calculado del lado de quien llama (ej: la receta hardcodeada
     * del catálogo) no rompe el descuento silenciosamente.
     */
    @Size(max = 255)
    private String materialNombre;

    @NotNull
    private TipoMovimiento tipo;

    @NotNull @Positive(message = "La cantidad debe ser mayor que cero")
    private Double cantidad;

    @Size(max = 255)
    private String motivo;

    private Long pedidoId;
}
