package com.gs.monolito.pedidos.repository;

import com.gs.monolito.pedidos.model.DocumentoPedido;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentoPedidoRepository extends JpaRepository<DocumentoPedido, Long> {
    List<DocumentoPedido> findByPedidoIdOrderByFechaSubidaDesc(Long pedidoId);
    long countByPedidoId(Long pedidoId);
}
