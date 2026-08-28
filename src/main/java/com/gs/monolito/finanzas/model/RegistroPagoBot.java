package com.gs.monolito.finanzas.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Bitácora de todo lo que procesa el bot de WhatsApp a partir de un
 * comprobante de pago (REGISTRADO/RECHAZADO/DUPLICADO). Persiste en
 * {@code gs_finanzas.registros_pago_bot}.
 */
@Entity
@Table(name = "registros_pago_bot", schema = "gs_finanzas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegistroPagoBot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fecha_hora", nullable = false, updatable = false)
    private LocalDateTime fechaHora;

    @Column(precision = 12, scale = 2)
    private BigDecimal monto;

    @Column(name = "id_operacion", length = 60)
    private String idOperacion;

    @Column(length = 200)
    private String emisor;

    @Column(name = "receptor_nombre", length = 200)
    private String receptorNombre;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_receptor", length = 15)
    private TipoReceptorBot tipoReceptor;

    @Column(name = "receptor_id")
    private Long receptorId;

    @Column(name = "receptor_resuelto", length = 200)
    private String receptorResuelto;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private EstadoRegistroBot estado;

    @Enumerated(EnumType.STRING)
    @Column(name = "fuente", length = 15, nullable = false)
    @Builder.Default
    private FuentePago fuente = FuentePago.TRANSFERENCIA;

    @Column(length = 300)
    private String mensaje;

    // ── Trazabilidad ──

    @Column(name = "cargado_por_nombre", length = 150)
    private String cargadoPorNombre;

    @Column(name = "cargado_por_telefono", length = 30)
    private String cargadoPorTelefono;

    @Column(name = "grupo_origen", length = 150)
    private String grupoOrigen;

    @Column(name = "comprobante_url", length = 300)
    private String comprobanteUrl;

    @PrePersist
    protected void onCreate() {
        if (this.fechaHora == null) this.fechaHora = LocalDateTime.now();
    }
}
