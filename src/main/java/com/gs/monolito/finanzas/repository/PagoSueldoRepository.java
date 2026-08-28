package com.gs.monolito.finanzas.repository;

import com.gs.monolito.finanzas.model.PagoSueldo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PagoSueldoRepository extends JpaRepository<PagoSueldo, Long> {

    List<PagoSueldo> findByEmpleadoIdOrderByFechaDescIdDesc(Long empleadoId);

    List<PagoSueldo> findAllByOrderByFechaDescIdDesc();

    boolean existsByIdOperacion(String idOperacion);
}
