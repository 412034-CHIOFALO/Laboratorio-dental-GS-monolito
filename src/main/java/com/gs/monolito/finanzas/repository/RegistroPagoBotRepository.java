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

    /** El mismo archivo ya registrado (mismo SHA-256), aunque el comprobante no traiga N° de operación. */
    boolean existsByHashComprobanteAndEstado(String hashComprobante, EstadoRegistroBot estado);

    /** El registro de un mensaje de WhatsApp ya procesado (como comprobante/comando o como pie). */
    java.util.Optional<RegistroPagoBot> findFirstByIdMensajeWaOrIdMensajePie(String idMensajeWa, String idMensajePie);

    @org.springframework.data.jpa.repository.Query("select r.idMensajeWa from RegistroPagoBot r where r.idMensajeWa in :ids")
    List<String> idsMensajeWaConocidos(@org.springframework.data.repository.query.Param("ids") java.util.Collection<String> ids);

    @org.springframework.data.jpa.repository.Query("select r.idMensajePie from RegistroPagoBot r where r.idMensajePie in :ids")
    List<String> idsMensajePieConocidos(@org.springframework.data.repository.query.Param("ids") java.util.Collection<String> ids);

    List<RegistroPagoBot> findByEstadoOrderByFechaHoraDesc(EstadoRegistroBot estado);
}
