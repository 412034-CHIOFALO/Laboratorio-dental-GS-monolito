package com.gs.monolito.pedidos.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Datos que se capturan al pasar un pedido de LISTO a ENTREGADO.
 */
@Data
public class EntregaRequest {

    @NotBlank(message = "Indicar quién retiró el trabajo es obligatorio")
    @Size(max = 150)
    private String retiradoPor;

    @PastOrPresent(message = "La fecha de entrega no puede ser futura")
    private LocalDate fechaEntregaReal;

    @Size(max = 1000)
    private String observacionesEntrega;

    /**
     * Monto a facturar (genera la deuda en cuenta corriente). Si no viene, se usa
     * el precio acordado del pedido. Si ninguno está, no se genera comprobante.
     */
    @Positive(message = "El monto a facturar debe ser mayor a cero")
    private BigDecimal monto;
}
