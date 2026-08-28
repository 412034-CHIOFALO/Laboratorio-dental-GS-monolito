package com.gs.monolito.finanzas.repository;

import com.gs.monolito.finanzas.model.Proveedor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProveedorRepository extends JpaRepository<Proveedor, Long> {

    List<Proveedor> findByActivoTrue();

    Optional<Proveedor> findByCuit(String cuit);

    boolean existsByCuit(String cuit);

    boolean existsByNombreIgnoreCase(String nombre);
}
