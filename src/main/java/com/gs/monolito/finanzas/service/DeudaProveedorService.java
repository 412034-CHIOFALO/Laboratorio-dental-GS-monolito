package com.gs.monolito.finanzas.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.common.security.CurrentUser;
import com.gs.monolito.finanzas.dto.DeudaProveedorRequest;
import com.gs.monolito.finanzas.dto.DeudaProveedorResponse;
import com.gs.monolito.finanzas.exception.BusinessException;
import com.gs.monolito.finanzas.exception.ResourceNotFoundException;
import com.gs.monolito.finanzas.model.CajaMovimiento;
import com.gs.monolito.finanzas.model.DeudaProveedor;
import com.gs.monolito.finanzas.model.EstadoDeuda;
import com.gs.monolito.finanzas.model.Proveedor;
import com.gs.monolito.finanzas.model.TipoCaja;
import com.gs.monolito.finanzas.model.TipoMovimientoCaja;
import com.gs.monolito.finanzas.repository.CajaMovimientoRepository;
import com.gs.monolito.finanzas.repository.DeudaProveedorRepository;
import com.gs.monolito.finanzas.repository.ProveedorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Implementación de {@link IDeudaProveedorService}. Al registrar un pago,
 * descuenta la deuda ({@code PENDIENTE} → {@code PAGADA}) y genera el egreso
 * correspondiente en la caja (Física para efectivo, Bancaria para
 * transferencias).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeudaProveedorService implements IDeudaProveedorService {

    private final DeudaProveedorRepository deudaRepo;
    private final ProveedorRepository proveedorRepo;
    private final CajaMovimientoRepository cajaMovimientoRepo;
    private final AuditoriaService auditoria;

    public List<DeudaProveedorResponse> listarPorProveedor(Long proveedorId) {
        return deudaRepo.findByProveedorIdOrderByFechaCreacionDesc(proveedorId).stream()
            .map(DeudaProveedorResponse::from)
            .toList();
    }

    public List<DeudaProveedorResponse> listarPendientes() {
        return deudaRepo.findByEstadoOrderByFechaVencimientoAsc(EstadoDeuda.PENDIENTE).stream()
            .map(DeudaProveedorResponse::from)
            .toList();
    }

    public List<DeudaProveedorResponse> listarPagadas() {
        return deudaRepo.findByEstadoOrderByFechaPagoDesc(EstadoDeuda.PAGADO).stream()
            .map(DeudaProveedorResponse::from)
            .toList();
    }

    public DeudaProveedorResponse buscarPorId(Long id) {
        return deudaRepo.findById(id)
            .map(DeudaProveedorResponse::from)
            .orElseThrow(() -> new ResourceNotFoundException("DeudaProveedor", id));
    }

    @Transactional
    public DeudaProveedorResponse registrar(DeudaProveedorRequest request) {
        Proveedor proveedor = proveedorRepo.findById(request.getProveedorId())
            .orElseThrow(() -> new ResourceNotFoundException("Proveedor", request.getProveedorId()));
        DeudaProveedor d = DeudaProveedor.builder()
            .proveedor(proveedor)
            .descripcion(request.getDescripcion())
            .monto(request.getMonto())
            .fechaVencimiento(request.getFechaVencimiento())
            .nroFacturaProveedor(request.getNroFacturaProveedor())
            .observaciones(request.getObservaciones())
            .build();
        return DeudaProveedorResponse.from(deudaRepo.save(d));
    }

    /**
     * Marca el SALDO RESTANTE de la deuda como pagado (no el monto original) —
     * si ya tenía pagos parciales (por ejemplo, un triangulado del bot que
     * cubrió parte), este botón manual solo cubre lo que falta, y el egreso de
     * caja es por ese resto, no por el total, para no duplicar contablemente
     * lo que ya se descontó antes.
     */
    @Transactional
    public DeudaProveedorResponse pagar(Long id, TipoCaja caja) {
        DeudaProveedor d = deudaRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("DeudaProveedor", id));
        if (d.getEstado() == EstadoDeuda.PAGADO) {
            throw new BusinessException("La deuda ya está marcada como pagada.");
        }
        java.math.BigDecimal restante = d.getSaldoPendiente();
        d.setMontoPagado(d.getMonto());
        d.setEstado(EstadoDeuda.PAGADO);
        d.setFechaPago(LocalDate.now());
        DeudaProveedor guardada = deudaRepo.save(d);

        if (restante.signum() > 0) {
            cajaMovimientoRepo.save(CajaMovimiento.builder()
                .tipo(TipoMovimientoCaja.EGRESO)
                .tipoCaja(caja)
                .monto(restante)
                .concepto("Pago a proveedor: " + d.getProveedor().getNombre() + " — " + d.getDescripcion())
                .referencia(d.getNroFacturaProveedor())
                .creadoPor("panel")
                .build());
        }

        auditoria.registrar(CurrentUser.usernameOrSistema(), "PROVEEDOR", "Pago a proveedor", "Proveedor " + d.getProveedor().getNombre(),
                "$" + restante + " por " + caja + " · " + d.getDescripcion());

        return DeudaProveedorResponse.from(guardada);
    }
}
