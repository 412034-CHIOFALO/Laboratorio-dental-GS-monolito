package com.gs.monolito.pedidos.dto;

import com.gs.monolito.pedidos.model.Prioridad;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class PedidoRequest {

    /**
     * Opcional. Si viene seteado se usa directamente; si viene null se busca o
     * crea un odontólogo a partir de {@link #odontologoNombre}.
     */
    private Long odontologoId;

    @NotBlank(message = "El nombre del odontólogo es obligatorio")
    @Size(max = 150, message = "El nombre del odontólogo no puede superar los 150 caracteres")
    private String odontologoNombre;

    @NotBlank(message = "El paciente es obligatorio")
    @Size(max = 150, message = "El nombre del paciente no puede superar los 150 caracteres")
    private String paciente;

    private Long catalogoTrabajoId;

    @NotBlank(message = "El trabajo es obligatorio")
    @Size(max = 200, message = "El trabajo no puede superar los 200 caracteres")
    private String trabajo;

    private Long tecnicoId;
    @Size(max = 150)
    private String tecnicoNombre;

    @NotNull(message = "La fecha de entrega es obligatoria")
    @Future(message = "La fecha de entrega debe ser futura")
    private LocalDate fechaEntrega;

    private Prioridad prioridad = Prioridad.NORMAL;

    @PositiveOrZero(message = "El precio no puede ser negativo")
    @Digits(integer = 10, fraction = 2, message = "El precio excede el máximo permitido")
    private BigDecimal precioAcordado;

    @Size(max = 1000, message = "Las observaciones no pueden superar los 1000 caracteres")
    private String observaciones;
}
