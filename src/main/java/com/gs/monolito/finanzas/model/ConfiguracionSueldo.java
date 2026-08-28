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
@Table(name = "configuracion_sueldo", schema = "gs_finanzas",
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
     * Última fecha hasta la que ya se calculó el devengo diario (ver
     * {@link com.gs.monolito.finanzas.service.GestionSueldoService#devengarDiario()}).
     * Null hasta el primer cálculo — ese caso se devenga desde
     * {@link #fechaCreacion} (el día de alta), prorrateando el ciclo incompleto.
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
    }

    @PreUpdate
    protected void onUpdate() {
        this.fechaModificacion = LocalDateTime.now();
    }
}
