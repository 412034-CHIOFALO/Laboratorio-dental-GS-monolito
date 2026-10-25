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

    /** Cuántos mails sin autorizar guardó ya un remitente desde cierta fecha (para que no pueda llenar la bandeja). */
    long countByRemitenteEmailAndEstadoAndFechaRecepcionAfter(String remitenteEmail, SolicitudEntrante.Estado estado,
                                                              java.time.LocalDateTime desde);

    /** Lo sin autorizar de un remitente (al bloquearlo se descarta todo junto). */
    List<SolicitudEntrante> findByRemitenteEmailAndEstado(String remitenteEmail, SolicitudEntrante.Estado estado);

    /** Los sin autorizar que ya vencieron (se borran: no se guarda indefinidamente lo que nadie reconoció). */
    long deleteByEstadoAndFechaRecepcionBefore(SolicitudEntrante.Estado estado, java.time.LocalDateTime antes);
}
