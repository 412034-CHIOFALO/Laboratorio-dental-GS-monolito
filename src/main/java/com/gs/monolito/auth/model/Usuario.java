package com.gs.monolito.auth.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Representa a un integrante del Laboratorio G&amp;S registrado en el sistema.
 * Persiste en {@code gs_auth.usuarios} — schema propio del módulo auth dentro
 * del único DataSource del monolito (ver {@link com.gs.monolito.common.security.JwtBeans}
 * para el porqué de mantener los schemas separados en vez de fusionarlos).
 */
@Entity
@Table(name = "usuarios", schema = "gs_auth")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;

    private String nombre;
    private String apellido;

    @Column(length = 30)
    private String telefono;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Rol rol;

    @Builder.Default
    private boolean enabled = false;

    @Builder.Default
    private boolean pendienteAprobacion = true;

    @Builder.Default
    private boolean terminosAceptados = false;

    private Instant fechaAceptacionTerminos;
}
