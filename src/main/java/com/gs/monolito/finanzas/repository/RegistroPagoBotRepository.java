package com.gs.monolito.finanzas.repository;

import com.gs.monolito.finanzas.model.EstadoRegistroBot;
import com.gs.monolito.finanzas.model.RegistroPagoBot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RegistroPagoBotRepository extends JpaRepository<RegistroPagoBot, Long> {

    List<RegistroPagoBot> findAllByOrderByFechaHoraDesc();

    boolean existsByIdOperacionAndEstado(String idOperacion, EstadoRegistroBot estado);

    List<RegistroPagoBot> findByEstadoOrderByFechaHoraDesc(EstadoRegistroBot estado);
}
