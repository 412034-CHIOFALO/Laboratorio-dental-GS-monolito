package com.gs.monolito.stock.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Movimiento de stock de un {@link Material} — ENTRADA, SALIDA o AJUSTE.
 * Persiste en {@code gs_stock.movimientos_stock}.
 */
@Entity
@Table(name = "movimientos_stock", schema = "gs_stock")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MovimientoStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", nullable = false)
    private Material material;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TipoMovimiento tipo;

    @Column(nullable = false)
    private Double cantidad;

    @Column(name = "stock_resultante", nullable = false)
    private Double stockResultante;

    @Column(length = 255)
    private String motivo;

    /** ID del pedido que originó este movimiento SALIDA (cross-módulo por id). */
    @Column(name = "pedido_id")
    private Long pedidoId;

    @Column(name = "fecha_movimiento", nullable = false, updatable = false)
    private LocalDateTime fechaMovimiento;

    @PrePersist
    protected void onCreate() {
        this.fechaMovimiento = LocalDateTime.now();
    }
}
