package com.gs.monolito.finanzas.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * La marca de un día en el calendario de un empleado (ausente, vacaciones, feriado...). Una sola fila por
 * empleado y día. Un día sin fila es un día "común": ver {@link TipoDia}.
 */
@Entity
@Table(name = "asistencia_empleado",
       uniqueConstraints = @UniqueConstraint(name = "uk_asistencia_empleado_fecha", columnNames = {"empleado_id", "fecha"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AsistenciaEmpleado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "empleado_id", nullable = false)
    private Long empleadoId;

    @Column(name = "fecha", nullable = false)
    private LocalDate fecha;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 15)
    private TipoDia tipo;

    @Column(name = "nota", length = 200)
    private String nota;

    @Column(name = "cargado_por", length = 100)
    private String cargadoPor;

    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private LocalDateTime fechaCreacion;

    @PrePersist
    protected void onCreate() {
        this.fechaCreacion = LocalDateTime.now();
    }
}
