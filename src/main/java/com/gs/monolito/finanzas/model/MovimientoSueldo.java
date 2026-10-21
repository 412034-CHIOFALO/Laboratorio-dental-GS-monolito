package com.gs.monolito.finanzas.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Libro de sueldos: cada vez que cambia el saldo de un empleado queda una línea con cuánto cambió, por qué
 * y cómo quedó. Solo se agrega, nunca se edita ni se borra: es lo que permite explicar un saldo.
 */
@Entity
@Table(name = "movimientos_sueldo",
       indexes = @Index(name = "ix_movimientos_sueldo_empleado", columnList = "empleado_id, fecha_creacion"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MovimientoSueldo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "empleado_id", nullable = false)
    private Long empleadoId;

    /** Fecha del hecho (día cerrado, día del pago...), no la de carga. */
    @Column(name = "fecha", nullable = false)
    private LocalDate fecha;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 20)
    private TipoMovimientoSueldo tipo;

    /** Con signo: positivo suma al saldo devengado (se le debe más), negativo resta. */
    @Column(name = "monto", nullable = false, precision = 12, scale = 2)
    private BigDecimal monto;

    @Column(name = "saldo_devengado", nullable = false, precision = 12, scale = 2)
    private BigDecimal saldoDevengado;

    @Column(name = "saldo_sobrante", nullable = false, precision = 12, scale = 2)
    private BigDecimal saldoSobrante;

    @Column(name = "detalle", length = 300)
    private String detalle;

    @Column(name = "usuario", length = 100)
    private String usuario;

    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private LocalDateTime fechaCreacion;

    @PrePersist
    protected void onCreate() {
        this.fechaCreacion = LocalDateTime.now();
    }
}
