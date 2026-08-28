package com.gs.monolito.stock.repository;

import com.gs.monolito.stock.model.CategoriaMaterial;
import com.gs.monolito.stock.model.Material;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MaterialRepository extends JpaRepository<Material, Long> {

    List<Material> findByActivoTrue();

    List<Material> findByCategoriaAndActivoTrue(CategoriaMaterial categoria);

    boolean existsByNombreIgnoreCase(String nombre);

    Optional<Material> findByNombreIgnoreCase(String nombre);

    @Query("SELECT m FROM Material m WHERE m.activo = true AND m.stockActual <= m.stockMinimo")
    List<Material> findBajoStock();
}
