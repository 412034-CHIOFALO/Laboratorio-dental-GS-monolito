package com.gs.monolito.auth.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Registro inmutable de un evento ocurrido en el sistema. Persiste en
 * {@code gs_auth.auditoria_eventos}. Los demás módulos registran eventos
 * llamando directo a {@link com.gs.monolito.auth.service.AuditoriaService}
 * (antes viajaba por HTTP entre microservicios vía {@code AuditoriaClient}).
 */
@Entity
@Table(name = "auditoria_eventos", schema = "gs_auth")
@Data
@NoArgsConstructor
public class AuditoriaEvento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(nullable = false, length = 100)
    private String usuario;

    @Column(nullable = false, length = 50)
    private String tipo;

    @Column(nullable = false, length = 200)
    private String accion;

    @Column(nullable = false, length = 200)
    private String entidad;

    @Column(length = 500)
    private String detalle;

    public AuditoriaEvento(String usuario, String tipo, String accion, String entidad, String detalle) {
        this.timestamp = Instant.now();
        this.usuario   = usuario;
        this.tipo      = tipo;
        this.accion    = accion;
        this.entidad   = entidad;
        this.detalle   = detalle != null ? detalle : "";
    }
}
