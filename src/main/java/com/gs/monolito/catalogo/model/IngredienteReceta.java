package com.gs.monolito.catalogo.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Línea de receta de un {@link TipoTrabajo}: un material del stock que se
 * consume para fabricar ese tipo de trabajo. Los materiales viven en el
 * módulo stock (schema {@code gs_stock}) — acá solo guardamos el id como
 * referencia y un snapshot del nombre, igual que antes (cross-schema por id,
 * nunca un @ManyToOne cruzando módulos).
 */
@Entity
@Table(name = "ingredientes_receta", schema = "gs_catalogo")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngredienteReceta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tipo_trabajo_id", nullable = false)
    private TipoTrabajo tipoTrabajo;

    @Column(name = "material_id", nullable = false)
    private Long materialId;

    @Column(name = "material_nombre", nullable = false, length = 200)
    private String materialNombre;

    @Column(length = 30)
    private String unidad;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal cantidad;

    @Column(columnDefinition = "TEXT")
    private String notas;
}
