package com.gs.monolito.finanzas.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Pago que un odontólogo realiza a su cuenta corriente, imputado a las deudas
 * pendientes de la más vieja a la más nueva. Persiste en
 * {@code gs_finanzas.pagos_cuenta_corriente}.
 */
@Entity
@Table(name = "pagos_cuenta_corriente", schema = "gs_finanzas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PagoCuentaCorriente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "odontologo_id", nullable = false)
    private Long odontologoId;

    @Column(name = "odontologo_nombre", nullable = false, length = 150)
    private String odontologoNombre;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal monto;

    @Column(name = "monto_imputado", nullable = false, precision = 12, scale = 2)
    private BigDecimal montoImputado;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MedioPago medio;

    @Column(nullable = false)
    private LocalDate fecha;

    @Column(length = 255)
    private String nota;

    @Column(name = "registrado_por", length = 100)
    private String registradoPor;

    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private LocalDateTime fechaCreacion;

    @PrePersist
    protected void onCreate() {
        this.fechaCreacion = LocalDateTime.now();
        if (this.fecha == null) this.fecha = LocalDate.now();
    }
}
