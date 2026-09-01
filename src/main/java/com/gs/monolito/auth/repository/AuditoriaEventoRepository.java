package com.gs.monolito.auth.repository;

import com.gs.monolito.auth.model.AuditoriaEvento;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditoriaEventoRepository extends JpaRepository<AuditoriaEvento, Long> {

    /**
     * tipo/q son opcionales — el patrón ":param IS NULL OR campo = :param" deja
     * pasar todo cuando no se manda el filtro, sin tener que armar la query a mano.
     */
    @Query("""
        SELECT e FROM AuditoriaEvento e
        WHERE (:tipo IS NULL OR e.tipo = :tipo)
          AND (:q IS NULL
               OR LOWER(e.usuario) LIKE CONCAT('%', :q, '%')
               OR LOWER(e.accion)  LIKE CONCAT('%', :q, '%')
               OR LOWER(e.entidad) LIKE CONCAT('%', :q, '%')
               OR LOWER(e.detalle) LIKE CONCAT('%', :q, '%'))
        """)
    Page<AuditoriaEvento> buscar(@Param("tipo") String tipo, @Param("q") String q, Pageable pageable);
}
