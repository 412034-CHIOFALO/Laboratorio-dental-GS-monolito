package com.gs.monolito.catalogo.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Fila única (id=1 fijo, sin auto_increment) que dice si el catálogo público
 * (/catalogo, sin login) está habilitado. Persiste en
 * {@code gs_catalogo.configuracion_catalogo_publico}. Ver
 * {@link com.gs.monolito.catalogo.service.ConfiguracionCatalogoPublicoService}
 * para la lógica de "crear la fila si no existe".
 */
@Entity
@Table(name = "configuracion_catalogo_publico", schema = "gs_catalogo")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfiguracionCatalogoPublico {

    public static final Long ID_UNICO = 1L;

    @Id
    private Long id;

    @Column(nullable = false)
    private boolean habilitado;

    @Column(name = "fecha_modificacion")
    private LocalDateTime fechaModificacion;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        this.fechaModificacion = LocalDateTime.now();
    }
}
