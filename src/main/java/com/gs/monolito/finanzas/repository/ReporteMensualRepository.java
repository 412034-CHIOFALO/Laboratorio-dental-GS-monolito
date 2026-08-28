package com.gs.monolito.finanzas.repository;

import com.gs.monolito.finanzas.model.ReporteMensual;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReporteMensualRepository extends JpaRepository<ReporteMensual, Long> {

    Optional<ReporteMensual> findByAnioAndMes(int anio, int mes);

    List<ReporteMensual> findAllByOrderByAnioDescMesDesc();
}
