package com.gs.monolito.finanzas.repository;

import com.gs.monolito.finanzas.model.Comprobante;
import com.gs.monolito.finanzas.model.EstadoPago;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface ComprobanteRepository extends JpaRepository<Comprobante, Long> {

    /** Usado para sincronizar el monto del comprobante si se corrige el precio del pedido ya entregado. */
    Optional<Comprobante> findByPedidoId(Long pedidoId);

    /**
     * Último número de comprobante emitido con un prefijo dado (ej: "COMP-202607-").
     * Como el sufijo va rellenado con ceros a 4 dígitos, el MAX() lexicográfico
     * coincide con el numérico. Se usa para numerar sin depender de count(), que
     * se desfasa apenas se borra un comprobante y regenera un número ya usado
     * (nro_comprobante es UNIQUE → la inserción fallaba).
     */
    @Query("SELECT MAX(c.nroComprobante) FROM Comprobante c WHERE c.nroComprobante LIKE CONCAT(:prefijo, '%')")
    String maxNroComprobanteConPrefijo(@org.springframework.data.repository.query.Param("prefijo") String prefijo);

    List<Comprobante> findByOdontologoId(Long odontologoId);

    List<Comprobante> findByEstadoPago(EstadoPago estadoPago);

    List<Comprobante> findByOdontologoIdAndEstadoPago(Long odontologoId, EstadoPago estadoPago);

    /** Comprobantes con saldo pendiente (PENDIENTE o PARCIAL) de un odontólogo. */
    List<Comprobante> findByOdontologoIdAndEstadoPagoIn(Long odontologoId, List<EstadoPago> estados);

    /** Saldo pendiente total de un odontólogo (cuenta corriente) = Σ(monto − pagado). */
    @Query("SELECT COALESCE(SUM(c.monto - c.montoPagado), 0) FROM Comprobante c " +
           "WHERE c.odontologoId = :odontologoId AND c.estadoPago IN ('PENDIENTE', 'PARCIAL')")
    BigDecimal sumMontosPendientesByOdontologo(Long odontologoId);

    /**
     * Ranking de morosos: una fila por odontólogo con saldo pendiente, cantidad
     * de comprobantes y fecha del comprobante más viejo (para calcular días
     * sin pagar).
     *
     * Proyectado como array de objetos por simplicidad (sin DTO en JPQL).
     * Estructura de cada row:
     *   [0] odontologoId    (Long)
     *   [1] odontologoNombre (String)
     *   [2] totalDeuda      (BigDecimal)
     *   [3] comprobantesPendientes (Long)
     *   [4] fechaMasAntigua (LocalDate)
     */
    @Query("""
        SELECT c.odontologoId,
               MAX(c.odontologoNombre),
               SUM(c.monto - c.montoPagado),
               COUNT(c),
               MIN(c.fechaEmision)
        FROM Comprobante c
        WHERE c.estadoPago IN ('PENDIENTE', 'PARCIAL')
        GROUP BY c.odontologoId
        ORDER BY SUM(c.monto - c.montoPagado) DESC
    """)
    List<Object[]> rankingDeudoresRaw();

    /**
     * Misma proyección que {@link #rankingDeudoresRaw()} pero sin filtrar por
     * estado — incluye también a los odontólogos que ya saldaron todo (quedan
     * con totalDeuda = 0). Sin esto, la pestaña "Todos" de Cuentas Corrientes
     * mostraba exactamente lo mismo que "Solo morosos": el dato de origen ya
     * venía pre-filtrado a deudores, así que no había nada que diferenciar.
     */
    @Query("""
        SELECT c.odontologoId,
               MAX(c.odontologoNombre),
               SUM(c.monto - c.montoPagado),
               COUNT(c),
               MIN(c.fechaEmision)
        FROM Comprobante c
        GROUP BY c.odontologoId
        ORDER BY SUM(c.monto - c.montoPagado) DESC
    """)
    List<Object[]> rankingTodosRaw();
}
