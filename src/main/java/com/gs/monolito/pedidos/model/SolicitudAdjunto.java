package com.gs.monolito.pedidos.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Un archivo adjunto a una {@link SolicitudEntrante}. Los escaneos 3D ya vienen inspeccionados (formato, triángulos,
 * medidas); al convertir la solicitud en pedido pasan a ser {@link EscaneosPedido} o {@link DocumentoPedido} sin
 * copiar el archivo: se reutiliza el mismo objeto de MinIO.
 */
@Entity
@Table(name = "solicitud_adjuntos")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SolicitudAdjunto {

    public enum Tipo { DOCUMENTO, ESCANEO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "solicitud_id", nullable = false)
    private Long solicitudId;

    @Column(name = "object_key", nullable = false, length = 500)
    private String objectKey;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "tamanio_bytes")
    private Long tamanioBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 10)
    private Tipo tipo;

    @Column(name = "sha256", length = 64, columnDefinition = "char(64)")
    private String sha256;

    @Column(name = "formato", length = 20)
    private String formato;

    @Column(name = "triangulos")
    private Long triangulos;

    @Column(name = "vertices")
    private Long vertices;

    @Column(name = "dim_x_mm", precision = 14, scale = 3)
    private BigDecimal dimXMm;

    @Column(name = "dim_y_mm", precision = 14, scale = 3)
    private BigDecimal dimYMm;

    @Column(name = "dim_z_mm", precision = 14, scale = 3)
    private BigDecimal dimZMm;

    @Column(name = "advertencias", length = 600)
    private String advertencias;

    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private LocalDateTime fechaCreacion;

    @PrePersist
    protected void onCreate() {
        this.fechaCreacion = LocalDateTime.now();
    }
}
