package com.gs.monolito.stock.repository;

import com.gs.monolito.stock.model.MovimientoStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MovimientoStockRepository extends JpaRepository<MovimientoStock, Long> {

    List<MovimientoStock> findByMaterialIdOrderByFechaMovimientoDesc(Long materialId);
}
