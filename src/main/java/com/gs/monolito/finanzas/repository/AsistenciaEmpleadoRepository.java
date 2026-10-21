package com.gs.monolito.finanzas.repository;

import com.gs.monolito.finanzas.model.AsistenciaEmpleado;
import com.gs.monolito.finanzas.model.TipoDia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface AsistenciaEmpleadoRepository extends JpaRepository<AsistenciaEmpleado, Long> {

    List<AsistenciaEmpleado> findByEmpleadoIdAndFechaBetween(Long empleadoId, LocalDate desde, LocalDate hasta);

    long countByEmpleadoIdAndTipoAndFechaBetween(Long empleadoId, TipoDia tipo, LocalDate desde, LocalDate hasta);
}
