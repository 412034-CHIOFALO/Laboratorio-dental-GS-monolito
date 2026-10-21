package com.gs.monolito.finanzas.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Configuración de sueldo de un integrante del laboratorio, ligada a un
 * usuario del módulo auth vía {@link #empleadoId}. Persiste en
 * {@code gs_finanzas.configuracion_sueldo}. Una sola fila por empleado.
 */
@Entity
@Table(name = "configuracion_sueldo",
       uniqueConstraints = @UniqueConstraint(columnNames = "empleado_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfiguracionSueldo {

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

    @Column(name = "empleado_id", nullable = false, unique = true)
    private Long empleadoId;

    @Column(name = "empleado_nombre", nullable = false, length = 150)
    private String empleadoNombre;

    @Column(name = "rol", length = 30)
    private String rol;

    @Column(name = "telefono", length = 30)
    private String telefono;

    @Column(name = "activo", nullable = false)
    @Builder.Default
    private boolean activo = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "frecuencia", nullable = false, length = 12)
    @Builder.Default
    private FrecuenciaPago frecuencia = FrecuenciaPago.MENSUAL;

    @Column(name = "monto_base", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal montoBase = BigDecimal.ZERO;

    @Column(name = "saldo_devengado", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal saldoDevengado = BigDecimal.ZERO;

    @Column(name = "saldo_sobrante", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal saldoSobrante = BigDecimal.ZERO;

    @Column(name = "ultimo_pago")
    private LocalDate ultimoPago;

    /**
     * Primer día que corresponde cobrar. Antes de esta fecha el empleado no existe para la liquidación
     * (un alta a mitad de mes cobra solo los días que le tocan). Al reactivar a alguien se mueve al día
     * de la reactivación: lo que pasó en el medio no se le devenga.
     */
    @Column(name = "fecha_alta", nullable = false)
    private LocalDate fechaAlta;

    /** Último día que corresponde cobrar; null mientras sigue trabajando. */
    @Column(name = "fecha_baja")
    private LocalDate fechaBaja;

    /** Cupo anual de vacaciones (14 días corridos es el mínimo de la ley para menos de 5 años de antigüedad). */
    @Column(name = "dias_vacaciones_anuales", nullable = false)
    @Builder.Default
    private int diasVacacionesAnuales = 14;

    /**
     * Fracción de centavo todavía no acreditada. El devengo de un día es una fracción del sueldo
     * (monto / días del mes) que casi nunca cae en centavos exactos; en vez de perder o inventar un
     * centavo por día, se acumula acá y se acredita cuando suma uno. Así un mes completo da EXACTAMENTE
     * el sueldo.
     */
    @Column(name = "resto_devengo", nullable = false, precision = 14, scale = 10)
    @Builder.Default
    private BigDecimal restoDevengo = BigDecimal.ZERO;

    /**
     * Hasta qué día se cerró el devengo: se devengó hasta el día ANTERIOR a esta fecha (inclusive), o sea
     * que el día de esta fecha todavía no se cerró. Null hasta el primer cálculo: en ese caso se devenga
     * desde {@link #fechaAlta}. Ver {@link com.gs.monolito.finanzas.service.DevengoService}.
     */
    @Column(name = "ultimo_devengo_calculado")
    private LocalDate ultimoDevengoCalculado;

    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private LocalDateTime fechaCreacion;

    @Column(name = "fecha_modificacion")
    private LocalDateTime fechaModificacion;

    @PrePersist
    protected void onCreate() {
        this.fechaCreacion = LocalDateTime.now();
        this.fechaModificacion = LocalDateTime.now();
        if (this.fechaAlta == null) this.fechaAlta = LocalDate.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.fechaModificacion = LocalDateTime.now();
    }
}
