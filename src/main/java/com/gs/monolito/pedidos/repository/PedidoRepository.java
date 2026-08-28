package com.gs.monolito.pedidos.repository;

import com.gs.monolito.pedidos.model.EstadoPedido;
import com.gs.monolito.pedidos.model.Pedido;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PedidoRepository extends JpaRepository<Pedido, Long> {

    List<Pedido> findByEstado(EstadoPedido estado);

    List<Pedido> findByOdontologoId(Long odontologoId);

    List<Pedido> findByTecnicoId(Long tecnicoId);

    List<Pedido> findByEstadoNot(EstadoPedido estado);

    Optional<Pedido> findByNroPedido(String nroPedido);

    boolean existsByNroPedido(String nroPedido);

    /**
     * Último número de pedido emitido con un prefijo dado. Se usa para numerar
     * sin depender de count(), que se desfasa apenas se borra un pedido y
     * regenera un número ya usado — nroPedido es UNIQUE, así que esa colisión
     * rompe la creación del siguiente pedido sin capturar.
     */
    @Query("SELECT MAX(p.nroPedido) FROM Pedido p WHERE p.nroPedido LIKE CONCAT(:prefijo, '%')")
    String maxNroPedidoConPrefijo(@Param("prefijo") String prefijo);

    /**
     * Fecha del último pedido de cada odontólogo — para calcular su "estado de
     * actividad" sin desactivarlos a mano. Cada row: [0] odontologoId, [1] última fecha.
     */
    @Query("SELECT p.odontologoId, MAX(p.fechaCreacion) FROM Pedido p GROUP BY p.odontologoId")
    List<Object[]> ultimaActividadPorOdontologo();
}
