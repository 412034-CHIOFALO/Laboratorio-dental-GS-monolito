package com.gs.monolito.pedidos.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Escaneo 3D adjunto a un pedido (STL, OBJ u otro formato digital dental).
 * El contenido binario no se persiste acá: solo {@link #objectKey} (MinIO,
 * prefijo {@code escaneos/}). Persiste en {@code gs_pedidos.escaneos_pedido}.
 */
@Entity
@Table(name = "escaneos_pedido")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EscaneosPedido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pedido_id", nullable = false)
    private Long pedidoId;

    @Column(name = "object_key", nullable = false, length = 500)
    private String objectKey;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "tamanio_bytes")
    private Long tamanioBytes;

    @Column(name = "descripcion", length = 255)
    private String descripcion;

    @Column(name = "subido_por", length = 150)
    private String subidoPor;

    /** Formato real, según lo que se encontró adentro: STL_BINARIO, STL_ASCII, OBJ, PLY_ASCII o PLY_BINARIO. Null en los escaneos anteriores a la inspección. */
    @Column(name = "formato", length = 20)
    private String formato;

    @Column(name = "triangulos")
    private Long triangulos;

    @Column(name = "vertices")
    private Long vertices;

    /** Medidas del modelo en las unidades del archivo (los escáneres dentales exportan milímetros). */
    @Column(name = "dim_x_mm", precision = 14, scale = 3)
    private BigDecimal dimXMm;

    @Column(name = "dim_y_mm", precision = 14, scale = 3)
    private BigDecimal dimYMm;

    @Column(name = "dim_z_mm", precision = 14, scale = 3)
    private BigDecimal dimZMm;

    /** SHA-256 del archivo: el mismo escaneo no se carga dos veces en un pedido. */
    @Column(name = "sha256", length = 64, columnDefinition = "char(64)")
    private String sha256;

    /** Avisos de la inspección (separados por " | "): no bloquearon la subida pero conviene mirarlos. */
    @Column(name = "advertencias", length = 600)
    private String advertencias;

    @Column(name = "fecha_subida", nullable = false, updatable = false)
    private LocalDateTime fechaSubida;

    @PrePersist
    protected void onCreate() {
        this.fechaSubida = LocalDateTime.now();
    }
}
