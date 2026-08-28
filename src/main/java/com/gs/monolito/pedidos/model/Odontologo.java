package com.gs.monolito.pedidos.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Odontólogo cliente del laboratorio. Se buscan/crean por nombre desde el
 * flujo "Nuevo pedido" (find-or-create). Persiste en
 * {@code gs_pedidos.odontologos}.
 */
@Entity
@Table(name = "odontologos", schema = "gs_pedidos", indexes = {
    @Index(name = "idx_odontologo_nombre",    columnList = "nombre"),
    @Index(name = "idx_odontologo_dni",       columnList = "dni"),
    @Index(name = "idx_odontologo_cuit",      columnList = "cuit"),
    @Index(name = "idx_odontologo_matricula", columnList = "matricula")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Odontologo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre", nullable = false, length = 150)
    private String nombre;

    @Column(name = "dni", length = 10, unique = true)
    private String dni;

    @Column(name = "cuit", length = 13, unique = true)
    private String cuit;

    @Column(name = "telefono", length = 30)
    private String telefono;

    @Column(name = "email", length = 100)
    private String email;

    @Column(name = "matricula", length = 30)
    private String matricula;

    @Column(name = "clinica", length = 200)
    private String clinica;

    @Column(name = "direccion", length = 250)
    private String direccion;

    @Column(name = "activo", nullable = false)
    @Builder.Default
    private Boolean activo = true;

    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private LocalDateTime fechaCreacion;

    @Column(name = "fecha_modificacion")
    private LocalDateTime fechaModificacion;

    @PrePersist
    protected void onCreate() {
        this.fechaCreacion = LocalDateTime.now();
        this.fechaModificacion = LocalDateTime.now();
        if (this.activo == null) this.activo = true;
    }

    @PreUpdate
    protected void onUpdate() {
        this.fechaModificacion = LocalDateTime.now();
    }
}
