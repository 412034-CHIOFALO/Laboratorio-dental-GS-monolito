package com.gs.monolito.finanzas.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Movimiento de dinero en una de las tres cajas del laboratorio (FISICA,
 * BANCARIA, COMPENSACION). Persiste en {@code gs_finanzas.caja_movimientos}.
 */
@Entity
@Table(name = "caja_movimientos", schema = "gs_finanzas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CajaMovimiento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TipoMovimientoCaja tipo;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_caja", nullable = false, length = 15)
    private TipoCaja tipoCaja;

    @Column(nullable = false, length = 300)
    private String concepto;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal monto;

    @Column(length = 50)
    private String referencia;

    @Column(name = "fecha_movimiento", nullable = false)
    private LocalDate fechaMovimiento;

    /** Nombre del usuario autenticado que registró el movimiento (claim sub del JWT). */
    @Column(name = "creado_por", length = 50)
    private String creadoPor;

    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private LocalDateTime fechaCreacion;

    @PrePersist
    protected void onCreate() {
        this.fechaCreacion = LocalDateTime.now();
        if (this.fechaMovimiento == null) this.fechaMovimiento = LocalDate.now();
    }
}
