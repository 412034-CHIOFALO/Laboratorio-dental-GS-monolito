package com.gs.monolito.finanzas.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Metadatos de un reporte financiero mensual generado y archivado por el
 * sistema. El PDF se almacena en MinIO; acá se guarda su ubicación
 * ({@code objectName}). Persiste en {@code gs_finanzas.reporte_mensual}.
 */
@Entity
@Table(name = "reporte_mensual", schema = "gs_finanzas", uniqueConstraints = @UniqueConstraint(columnNames = {"anio", "mes"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReporteMensual {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private int anio;

    @Column(nullable = false)
    private int mes;

    @Column(name = "generado_en", nullable = false)
    private LocalDateTime generadoEn;

    @Column(name = "object_name", nullable = false, length = 300)
    private String objectName;

    @Column(name = "nombre_archivo", nullable = false, length = 120)
    private String nombreArchivo;

    @Column(nullable = false)
    private boolean automatico;
}
