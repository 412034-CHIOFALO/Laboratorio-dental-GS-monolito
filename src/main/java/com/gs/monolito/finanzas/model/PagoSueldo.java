package com.gs.monolito.finanzas.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Registro histórico de un pago realizado a un integrante del laboratorio.
 * Descuenta del saldo devengado de su {@link ConfiguracionSueldo}. Persiste
 * en {@code gs_finanzas.pagos_sueldo}.
 */
@Entity
@Table(name = "pagos_sueldo", schema = "gs_finanzas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PagoSueldo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "empleado_id", nullable = false)
    private Long empleadoId;

    @Column(name = "empleado_nombre", nullable = false, length = 150)
    private String empleadoNombre;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal monto;

    @Column(nullable = false)
    private LocalDate fecha;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    @Builder.Default
    private OrigenPago origen = OrigenPago.MANUAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "manejo_sobrante", length = 20)
    private ManejoSobrante manejoSobrante;

    @Column(name = "id_operacion", length = 60)
    private String idOperacion;

    @Column(name = "monto_excedente", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal montoExcedente = BigDecimal.ZERO;

    @Column(length = 300)
    private String nota;

    // ── Datos del bot (solo si origen = BOT_WHATSAPP) ──

    @Column(name = "cargado_por_nombre", length = 150)
    private String cargadoPorNombre;

    @Column(name = "cargado_por_telefono", length = 30)
    private String cargadoPorTelefono;

    @Column(name = "emisor", length = 150)
    private String emisor;

    @Column(name = "comprobante_url", length = 400)
    private String comprobanteUrl;

    @Column(name = "grupo_origen", length = 100)
    private String grupoOrigen;

    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private LocalDateTime fechaCreacion;

    @PrePersist
    protected void onCreate() {
        this.fechaCreacion = LocalDateTime.now();
        if (this.fecha == null) this.fecha = LocalDate.now();
    }
}
