package com.gs.monolito.finanzas.repository;

import com.gs.monolito.finanzas.model.ConfiguracionSueldo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConfiguracionSueldoRepository extends JpaRepository<ConfiguracionSueldo, Long> {

    Optional<ConfiguracionSueldo> findByEmpleadoId(Long empleadoId);

    boolean existsByEmpleadoId(Long empleadoId);

    List<ConfiguracionSueldo> findAllByOrderByEmpleadoNombreAsc();

    List<ConfiguracionSueldo> findByActivoTrueOrderByEmpleadoNombreAsc();

    @Query("SELECT COALESCE(SUM(c.saldoDevengado), 0) FROM ConfiguracionSueldo c WHERE c.activo = true")
    BigDecimal totalDevengado();
}
