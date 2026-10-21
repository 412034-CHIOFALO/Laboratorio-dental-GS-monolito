package com.gs.monolito.pedidos.repository;

import com.gs.monolito.pedidos.model.SolicitudAdjunto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SolicitudAdjuntoRepository extends JpaRepository<SolicitudAdjunto, Long> {

    List<SolicitudAdjunto> findBySolicitudIdOrderByIdAsc(Long solicitudId);

    long countBySolicitudId(Long solicitudId);
}
