package com.gs.monolito.finanzas.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Comprobante de deuda emitido a un odontólogo por un trabajo terminado.
 * Persiste en {@code gs_finanzas.comprobantes}. Corresponde a exactamente un
 * pedido del módulo pedidos (referencia por id, cross-módulo).
 */
@Entity
@Table(name = "comprobantes", schema = "gs_finanzas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Comprobante {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nro_comprobante", unique = true, nullable = false, length = 30)
    private String nroComprobante;

    @Column(name = "pedido_id", nullable = false)
    private Long pedidoId;

    @Column(name = "nro_pedido", nullable = false, length = 30)
    private String nroPedido;

    @Column(name = "odontologo_id", nullable = false)
    private Long odontologoId;

    @Column(name = "odontologo_nombre", nullable = false, length = 150)
    private String odontologoNombre;

    @Column(name = "trabajo", nullable = false, length = 200)
    private String trabajo;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal monto;

    @Column(name = "monto_pagado", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal montoPagado = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado_pago", nullable = false, length = 20)
    private EstadoPago estadoPago;

    @Column(name = "fecha_emision", nullable = false)
    private LocalDate fechaEmision;

    @Column(name = "fecha_vencimiento")
    private LocalDate fechaVencimiento;

    @Column(name = "fecha_cobro")
    private LocalDate fechaCobro;

    @Column(length = 255)
    private String observaciones;

    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private LocalDateTime fechaCreacion;

    @PrePersist
    protected void onCreate() {
        this.fechaCreacion = LocalDateTime.now();
        if (this.estadoPago == null) this.estadoPago = EstadoPago.PENDIENTE;
        if (this.montoPagado == null) this.montoPagado = BigDecimal.ZERO;
    }

    @Transient
    public BigDecimal getSaldoPendiente() {
        BigDecimal pagado = montoPagado != null ? montoPagado : BigDecimal.ZERO;
        return monto.subtract(pagado).max(BigDecimal.ZERO);
    }
}
