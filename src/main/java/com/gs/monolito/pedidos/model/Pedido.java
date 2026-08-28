package com.gs.monolito.pedidos.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Entidad principal del módulo: un trabajo dental encargado por un
 * odontólogo. Nace en {@link EstadoPedido#RECIBIDO} y avanza hasta
 * {@link EstadoPedido#ENTREGADO} (o {@link EstadoPedido#CANCELADO}).
 * Persiste en {@code gs_pedidos.pedidos}.
 *
 * <p>Referencias cross-módulo por id (nunca @ManyToOne): {@link #odontologoId}/
 * {@link #tecnicoId} → módulo auth, {@link #catalogoTrabajoId} → módulo
 * catalogo. El descuento de stock al pasar a EN_PROCESO llama directo al
 * módulo stock (antes vía Feign).</p>
 */
@Entity
@Table(name = "pedidos", schema = "gs_pedidos")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Pedido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nro_pedido", unique = true, nullable = false, length = 20)
    private String nroPedido;

    @Column(name = "odontologo_id", nullable = false)
    private Long odontologoId;

    @Column(name = "odontologo_nombre", nullable = false, length = 150)
    private String odontologoNombre;

    @Column(name = "paciente", nullable = false, length = 150)
    private String paciente;

    @Column(name = "catalogo_trabajo_id")
    private Long catalogoTrabajoId;

    @Column(name = "trabajo", nullable = false, length = 200)
    private String trabajo;

    @Column(name = "tecnico_id")
    private Long tecnicoId;

    @Column(name = "tecnico_nombre", length = 150)
    private String tecnicoNombre;

    @Column(name = "fecha_entrega", nullable = false)
    private LocalDate fechaEntrega;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 20)
    private EstadoPedido estado;

    @Enumerated(EnumType.STRING)
    @Column(name = "prioridad", nullable = false, length = 10)
    private Prioridad prioridad;

    @Column(name = "precio_acordado", precision = 12, scale = 2)
    private BigDecimal precioAcordado;

    @Column(name = "observaciones", columnDefinition = "TEXT")
    private String observaciones;

    // ── Datos de entrega (se completan al pasar a ENTREGADO) ──

    @Column(name = "fecha_entrega_real")
    private LocalDate fechaEntregaReal;

    @Column(name = "retirado_por", length = 150)
    private String retiradoPor;

    @Column(name = "observaciones_entrega", columnDefinition = "TEXT")
    private String observacionesEntrega;

    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private LocalDateTime fechaCreacion;

    @Column(name = "fecha_ultima_modificacion")
    private LocalDateTime fechaUltimaModificacion;

    // ── Consumo de stock automático ──

    /** Flag de idempotencia: evita descontar stock dos veces si el pedido reingresa a EN_PROCESO. */
    @Column(name = "stock_consumido", nullable = false)
    @Builder.Default
    private boolean stockConsumido = false;

    @Column(name = "fecha_stock_consumido")
    private LocalDateTime fechaStockConsumido;

    /** Flag de idempotencia: evita duplicar la deuda en finanzas al entregar. */
    @Column(name = "comprobante_generado", nullable = false)
    @Builder.Default
    private boolean comprobanteGenerado = false;

    @PrePersist
    protected void onCreate() {
        this.fechaCreacion = LocalDateTime.now();
        this.fechaUltimaModificacion = LocalDateTime.now();
        if (this.estado == null) this.estado = EstadoPedido.RECIBIDO;
        if (this.prioridad == null) this.prioridad = Prioridad.NORMAL;
    }

    @PreUpdate
    protected void onUpdate() {
        this.fechaUltimaModificacion = LocalDateTime.now();
    }
}
