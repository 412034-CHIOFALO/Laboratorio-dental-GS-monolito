package com.gs.monolito.pedidos.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Un pedido que llegó por un canal que no es el panel (hoy, el mail de un odontólogo) y todavía no es un
 * {@link Pedido}: una persona lo revisa y lo convierte en pedido o lo descarta. Los campos {@code sug*} son lo que el
 * sistema pudo extraer del texto por reglas; son una sugerencia para completar el formulario, no un dato confirmado.
 */
@Entity
@Table(name = "solicitudes_entrantes",
       uniqueConstraints = @UniqueConstraint(name = "uk_solicitud_message_id", columnNames = "message_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SolicitudEntrante {

    public enum Canal { EMAIL, WEB, WHATSAPP }

    public enum Estado { NUEVA, CONVERTIDA, DESCARTADA }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "version", nullable = false, columnDefinition = "bigint not null default 0")
    private Long version;

    @Enumerated(EnumType.STRING)
    @Column(name = "canal", nullable = false, length = 10)
    @Builder.Default
    private Canal canal = Canal.EMAIL;

    /** Identificador del mensaje en su canal (Message-ID del mail): es lo que impide cargarlo dos veces. */
    @Column(name = "message_id", nullable = false, length = 255)
    private String messageId;

    @Column(name = "remitente_email", length = 150)
    private String remitenteEmail;

    @Column(name = "remitente_nombre", length = 150)
    private String remitenteNombre;

    @Column(name = "asunto", length = 255)
    private String asunto;

    @Column(name = "cuerpo", columnDefinition = "TEXT")
    private String cuerpo;

    @Column(name = "fecha_mensaje")
    private LocalDateTime fechaMensaje;

    @Column(name = "fecha_recepcion", nullable = false, updatable = false)
    private LocalDateTime fechaRecepcion;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 12)
    @Builder.Default
    private Estado estado = Estado.NUEVA;

    /** El odontólogo cuyo email coincide con el remitente; null si no se pudo reconocer. */
    @Column(name = "odontologo_id")
    private Long odontologoId;

    @Column(name = "odontologo_nombre", length = 150)
    private String odontologoNombre;

    @Column(name = "sug_paciente", length = 150)
    private String sugPaciente;

    @Column(name = "sug_trabajo", length = 200)
    private String sugTrabajo;

    @Column(name = "sug_catalogo_trabajo_id")
    private Long sugCatalogoTrabajoId;

    @Column(name = "sug_fecha_entrega")
    private LocalDate sugFechaEntrega;

    @Enumerated(EnumType.STRING)
    @Column(name = "sug_prioridad", length = 10)
    private Prioridad sugPrioridad;

    @Column(name = "sug_precio", precision = 12, scale = 2)
    private BigDecimal sugPrecio;

    @Column(name = "sug_observaciones", columnDefinition = "TEXT")
    private String sugObservaciones;

    /** Adjuntos que no se guardaron (tipo no permitido, muy pesados, escaneo corrupto...), con el motivo. */
    @Column(name = "nota_adjuntos", length = 600)
    private String notaAdjuntos;

    @Column(name = "pedido_id")
    private Long pedidoId;

    @Column(name = "motivo_descarte", length = 255)
    private String motivoDescarte;

    @Column(name = "resuelto_por", length = 100)
    private String resueltoPor;

    @Column(name = "fecha_resolucion")
    private LocalDateTime fechaResolucion;

    @PrePersist
    protected void onCreate() {
        if (this.fechaRecepcion == null) this.fechaRecepcion = LocalDateTime.now();
    }
}
