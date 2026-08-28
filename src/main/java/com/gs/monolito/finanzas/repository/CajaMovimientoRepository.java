package com.gs.monolito.finanzas.repository;

import com.gs.monolito.finanzas.model.CajaMovimiento;
import com.gs.monolito.finanzas.model.TipoCaja;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface CajaMovimientoRepository extends JpaRepository<CajaMovimiento, Long> {

    List<CajaMovimiento> findByTipoCajaOrderByFechaMovimientoDesc(TipoCaja tipoCaja);

    List<CajaMovimiento> findByReferenciaOrderByFechaCreacionDesc(String referencia);

    @Query("SELECT COALESCE(SUM(CASE WHEN m.tipo = 'INGRESO' THEN m.monto ELSE -m.monto END), 0) FROM CajaMovimiento m WHERE m.tipoCaja = :tipoCaja")
    BigDecimal calcularSaldo(TipoCaja tipoCaja);

    List<CajaMovimiento> findByFechaMovimientoBetweenOrderByFechaMovimientoDesc(LocalDate desde, LocalDate hasta);
}
