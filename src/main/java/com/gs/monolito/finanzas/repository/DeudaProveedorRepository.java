package com.gs.monolito.finanzas.repository;

import com.gs.monolito.finanzas.model.DeudaProveedor;
import com.gs.monolito.finanzas.model.EstadoDeuda;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface DeudaProveedorRepository extends JpaRepository<DeudaProveedor, Long> {

    List<DeudaProveedor> findByProveedorIdOrderByFechaCreacionDesc(Long proveedorId);

    List<DeudaProveedor> findByEstadoOrderByFechaVencimientoAsc(EstadoDeuda estado);

    /** Deudas pagadas manualmente, más recientes primero — para el historial de Triangulados. */
    List<DeudaProveedor> findByEstadoOrderByFechaPagoDesc(EstadoDeuda estado);

    /** Deudas con saldo (PENDIENTE o PARCIAL) de un proveedor, más viejas primero. */
    List<DeudaProveedor> findByProveedorIdAndEstadoInOrderByFechaCreacionAsc(Long proveedorId, List<EstadoDeuda> estados);

    @Query("SELECT COALESCE(SUM(d.monto - d.montoPagado), 0) FROM DeudaProveedor d WHERE d.proveedor.id = :proveedorId AND d.estado IN ('PENDIENTE', 'PARCIAL')")
    BigDecimal sumDeudaPendienteByProveedor(Long proveedorId);

    @Query("SELECT COALESCE(SUM(d.monto - d.montoPagado), 0) FROM DeudaProveedor d WHERE d.estado IN ('PENDIENTE', 'PARCIAL')")
    BigDecimal sumTotalDeudaPendiente();
}
