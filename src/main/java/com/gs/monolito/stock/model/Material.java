package com.gs.monolito.stock.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Material o insumo del inventario del laboratorio. Persiste en
 * {@code gs_stock.materiales}. El stock se descuenta cuando se registra un
 * pedido que consume este material según la receta del catálogo.
 */
@Entity
@Table(name = "materiales", schema = "gs_stock")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Material {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Bloqueo optimista: dos ediciones simultáneas no se pisan (ver V3__version_bloqueo_optimista.sql). */
    // OJO con el DEFAULT 0: las tablas reales viven en gs_auth y las crea/actualiza Hibernate
    // (V3 solo toca los schemas viejos gs_pedidos/gs_stock/...). Sin el default, la columna se
    // agrega a las filas YA existentes con NULL y Hibernate tira NPE en Versioning.increment
    // al actualizarlas (500 en cada cambio de stock, pedido o pago).
    @Version
    @Column(name = "version", nullable = false, columnDefinition = "bigint not null default 0")
    private Long version;

    @Column(nullable = false, length = 200)
    private String nombre;

    @Column(columnDefinition = "TEXT")
    private String descripcion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CategoriaMaterial categoria;

    @Column(name = "stock_actual", nullable = false)
    private Double stockActual;

    @Column(name = "stock_minimo", nullable = false)
    private Double stockMinimo;

    @Column(name = "unidad_medida", nullable = false, length = 20)
    private String unidadMedida;

    @Column(name = "precio_unitario", precision = 12, scale = 2)
    private BigDecimal precioUnitario;

    @Column(length = 100)
    private String proveedor;

    @Column(nullable = false)
    @Builder.Default
    private boolean activo = true;

    /**
     * true = descuento normal (stockActual -= cantidadUsada).
     * false = solo verificación de existencia (esmaltes, pinceles, "uso por pinceladas").
     */
    @Column(name = "descuenta_stock", nullable = false)
    @Builder.Default
    private boolean descuentaStock = true;

    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private LocalDateTime fechaCreacion;

    @Column(name = "fecha_modificacion")
    private LocalDateTime fechaModificacion;

    @PrePersist
    protected void onCreate() {
        this.fechaCreacion = LocalDateTime.now();
        this.fechaModificacion = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.fechaModificacion = LocalDateTime.now();
    }
}
