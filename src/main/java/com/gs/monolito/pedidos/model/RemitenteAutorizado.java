package com.gs.monolito.pedidos.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Una dirección de mail con la que alguien del laboratorio decidió algo: <b>autorizarla</b> (sus mails entran
 * directo a "Por revisar") o <b>bloquearla</b> (sus mails se ignoran). Existe para no tener que creerle a cualquiera:
 * saber que un mail "dice" venir de un odontólogo no prueba que lo sea, y una secretaria que escribe en nombre del
 * odontólogo tiene su propia casilla.
 *
 * <p>El email de un odontólogo cargado en su ficha cuenta como autorizado sin estar acá; esta tabla es para lo demás:
 * secretarias, otras direcciones de un mismo odontólogo y los bloqueos.</p>
 */
@Entity
@Table(name = "remitentes_autorizados")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RemitenteAutorizado {

    public enum Estado { AUTORIZADO, BLOQUEADO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** En minúsculas: es la clave. */
    @Column(name = "email", nullable = false, length = 150, unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 12)
    private SolicitudEntrante.RemitenteTipo tipo;

    /** Odontólogo al que representa (para ODONTOLOGO y SECRETARIA). */
    @Column(name = "odontologo_id")
    private Long odontologoId;

    /** Cómo se lo muestra: "Secretaria de Dr. Pérez". */
    @Column(name = "etiqueta", length = 150)
    private String etiqueta;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 12)
    @Builder.Default
    private Estado estado = Estado.AUTORIZADO;

    @Column(name = "creado_por", nullable = false, length = 100)
    private String creadoPor;

    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private LocalDateTime fechaCreacion;
}
