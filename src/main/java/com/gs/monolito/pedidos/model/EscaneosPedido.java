package com.gs.monolito.pedidos.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Escaneo 3D adjunto a un pedido (STL, OBJ u otro formato digital dental).
 * El contenido binario no se persiste acá: solo {@link #objectKey} (MinIO,
 * prefijo {@code escaneos/}). Persiste en {@code gs_pedidos.escaneos_pedido}.
 */
@Entity
@Table(name = "escaneos_pedido", schema = "gs_pedidos")
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

    @Column(name = "fecha_subida", nullable = false, updatable = false)
    private LocalDateTime fechaSubida;

    @PrePersist
    protected void onCreate() {
        this.fechaSubida = LocalDateTime.now();
    }
}
