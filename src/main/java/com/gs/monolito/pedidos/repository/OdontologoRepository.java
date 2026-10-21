package com.gs.monolito.pedidos.repository;

import com.gs.monolito.pedidos.model.Odontologo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OdontologoRepository extends JpaRepository<Odontologo, Long> {

    List<Odontologo> findByActivoTrueOrderByNombreAsc();

    List<Odontologo> findAllByOrderByNombreAsc();

    List<Odontologo> findByActivoTrueAndNombreContainingIgnoreCaseOrderByNombreAsc(String fragmento);

    Optional<Odontologo> findByActivoTrueAndNombreIgnoreCase(String nombre);

    Optional<Odontologo> findByActivoTrueAndDni(String dni);
    Optional<Odontologo> findByActivoTrueAndCuit(String cuit);

    /** Los odontólogos activos que escriben desde ese email (puede haber más de uno si comparten la casilla de la clínica). */
    List<Odontologo> findByActivoTrueAndEmailIgnoreCase(String email);
    Optional<Odontologo> findByActivoTrueAndMatriculaIgnoreCase(String matricula);

    boolean existsByDni(String dni);
    boolean existsByCuit(String cuit);
}
