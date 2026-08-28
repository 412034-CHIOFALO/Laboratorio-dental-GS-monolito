package com.gs.monolito.catalogo.repository;

import com.gs.monolito.catalogo.model.Categoria;
import com.gs.monolito.catalogo.model.TipoTrabajo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TipoTrabajoRepository extends JpaRepository<TipoTrabajo, Long> {

    List<TipoTrabajo> findByActivoTrue();

    List<TipoTrabajo> findByCategoriaAndActivoTrue(Categoria categoria);

    List<TipoTrabajo> findByNombreContainingIgnoreCaseAndActivoTrue(String nombre);

    Optional<TipoTrabajo> findByNombreIgnoreCase(String nombre);
}
