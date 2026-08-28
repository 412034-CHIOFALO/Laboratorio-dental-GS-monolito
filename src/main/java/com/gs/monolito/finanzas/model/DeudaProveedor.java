package com.gs.monolito.finanzas.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Deuda del laboratorio con un proveedor de materiales. Persiste en
 * {@code gs_finanzas.deudas_proveedores}.
 */
@Entity
@Table(name = "deudas_proveedores", schema = "gs_finanzas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeudaProveedor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proveedor_id", nullable = false)
    private Proveedor proveedor;

    @Column(nullable = false, length = 300)
    private String descripcion;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal monto;

    @Column(name = "monto_pagado", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal montoPagado = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private EstadoDeuda estado = EstadoDeuda.PENDIENTE;

    @Column(name = "fecha_vencimiento")
    private LocalDate fechaVencimiento;

    @Column(name = "fecha_pago")
    private LocalDate fechaPago;

    @Column(name = "nro_factura_proveedor", length = 50)
    private String nroFacturaProveedor;

    @Column(name = "observaciones", length = 300)
    private String observaciones;

    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private LocalDateTime fechaCreacion;

    @PrePersist
    protected void onCreate() {
        this.fechaCreacion = LocalDateTime.now();
        if (this.montoPagado == null) this.montoPagado = BigDecimal.ZERO;
    }

    @Transient
    public BigDecimal getSaldoPendiente() {
        BigDecimal pagado = montoPagado != null ? montoPagado : BigDecimal.ZERO;
        return monto.subtract(pagado).max(BigDecimal.ZERO);
    }
}
