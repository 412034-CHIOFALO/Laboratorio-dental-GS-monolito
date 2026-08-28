package com.gs.monolito.auth.repository;

import com.gs.monolito.auth.model.AuditoriaEvento;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditoriaEventoRepository extends JpaRepository<AuditoriaEvento, Long> {
    List<AuditoriaEvento> findAllByOrderByTimestampDesc();
}
