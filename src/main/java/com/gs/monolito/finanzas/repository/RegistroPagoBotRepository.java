package com.gs.monolito.finanzas.repository;

import com.gs.monolito.finanzas.model.EstadoRegistroBot;
import com.gs.monolito.finanzas.model.RegistroPagoBot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RegistroPagoBotRepository extends JpaRepository<RegistroPagoBot, Long> {

    List<RegistroPagoBot> findAllByOrderByFechaHoraDesc();

    /**
     * El registro más viejo que ya tiene alguno de esos N° de operación (se pasa el valor leído y su versión
     * normalizada) en alguno de esos estados, sin contar al propio registro ({@code idPropio}; 0 si todavía no existe).
     */
    java.util.Optional<RegistroPagoBot> findFirstByIdOperacionInAndEstadoInAndIdNotOrderByIdAsc(
            java.util.Collection<String> operaciones, java.util.Collection<EstadoRegistroBot> estados, Long idPropio);

    /** Lo mismo para el mismo archivo (mismo SHA-256), aunque el comprobante no traiga N° de operación. */
    java.util.Optional<RegistroPagoBot> findFirstByHashComprobanteAndEstadoInAndIdNotOrderByIdAsc(
            String hashComprobante, java.util.Collection<EstadoRegistroBot> estados, Long idPropio);

    /** Registros del mismo monto cargados desde {@code desde}: candidatos a "se parece a otro comprobante". */
    List<RegistroPagoBot> findByMontoAndEstadoInAndFechaHoraAfterAndIdNot(
            java.math.BigDecimal monto, java.util.Collection<EstadoRegistroBot> estados,
            java.time.LocalDateTime desde, Long idPropio);

    /** El registro de un mensaje de WhatsApp ya procesado (como comprobante/comando o como pie). */
    java.util.Optional<RegistroPagoBot> findFirstByIdMensajeWaOrIdMensajePie(String idMensajeWa, String idMensajePie);

    @org.springframework.data.jpa.repository.Query("select r.idMensajeWa from RegistroPagoBot r where r.idMensajeWa in :ids")
    List<String> idsMensajeWaConocidos(@org.springframework.data.repository.query.Param("ids") java.util.Collection<String> ids);

    @org.springframework.data.jpa.repository.Query("select r.idMensajePie from RegistroPagoBot r where r.idMensajePie in :ids")
    List<String> idsMensajePieConocidos(@org.springframework.data.repository.query.Param("ids") java.util.Collection<String> ids);

    List<RegistroPagoBot> findByEstadoOrderByFechaHoraDesc(EstadoRegistroBot estado);

    List<RegistroPagoBot> findByEstadoAndFuenteOrderByFechaHoraDesc(EstadoRegistroBot estado, com.gs.monolito.finanzas.model.FuentePago fuente);
}
