package com.gs.monolito.finanzas.repository;

import com.gs.monolito.finanzas.model.MovimientoSueldo;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MovimientoSueldoRepository extends JpaRepository<MovimientoSueldo, Long> {

    /** Los más recientes primero; el {@link Pageable} acota cuántos. */
    List<MovimientoSueldo> findByEmpleadoIdOrderByFechaCreacionDescIdDesc(Long empleadoId, Pageable limite);
}
