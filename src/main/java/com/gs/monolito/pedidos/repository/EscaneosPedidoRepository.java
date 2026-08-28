package com.gs.monolito.pedidos.repository;

import com.gs.monolito.pedidos.model.EscaneosPedido;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EscaneosPedidoRepository extends JpaRepository<EscaneosPedido, Long> {
    List<EscaneosPedido> findByPedidoIdOrderByFechaSubidaDesc(Long pedidoId);
    long countByPedidoId(Long pedidoId);
}
