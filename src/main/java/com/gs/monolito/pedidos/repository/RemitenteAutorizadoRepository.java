package com.gs.monolito.pedidos.repository;

import com.gs.monolito.pedidos.model.RemitenteAutorizado;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RemitenteAutorizadoRepository extends JpaRepository<RemitenteAutorizado, Long> {

    Optional<RemitenteAutorizado> findByEmailIgnoreCase(String email);

    List<RemitenteAutorizado> findAllByOrderByEmailAsc();
}
