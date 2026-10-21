package com.gs.monolito.pedidos.repository;

import com.gs.monolito.pedidos.model.SolicitudEntrante;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SolicitudEntranteRepository extends JpaRepository<SolicitudEntrante, Long> {

    Optional<SolicitudEntrante> findByMessageId(String messageId);

    boolean existsByMessageId(String messageId);

    /** Las más nuevas primero. */
    List<SolicitudEntrante> findByEstadoOrderByFechaRecepcionDesc(SolicitudEntrante.Estado estado);

    long countByEstado(SolicitudEntrante.Estado estado);
}
