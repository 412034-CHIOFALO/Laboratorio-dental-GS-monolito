package com.gs.monolito.finanzas.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.common.security.CurrentUser;
import com.gs.monolito.finanzas.dto.CajaMovimientoRequest;
import com.gs.monolito.finanzas.dto.CajaMovimientoResponse;
import com.gs.monolito.finanzas.dto.DescuadreCompensacionResponse;
import com.gs.monolito.finanzas.dto.ResumenCajasResponse;
import com.gs.monolito.finanzas.model.CajaMovimiento;
import com.gs.monolito.finanzas.model.TipoCaja;
import com.gs.monolito.finanzas.model.TipoMovimientoCaja;
import com.gs.monolito.finanzas.repository.CajaMovimientoRepository;
import com.gs.monolito.finanzas.repository.ConfiguracionSueldoRepository;
import com.gs.monolito.finanzas.repository.DeudaProveedorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implementación de {@link ICajaService} para el sistema de tres cajas.
 * COMPENSACION es la caja auxiliar de triangulados: cada uno genera un
 * ingreso y un egreso equivalentes con la misma {@code referencia}, cuya
 * suma neta debe ser siempre $0; cualquier descuadre se detecta y reporta
 * en {@link #obtenerResumen()}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CajaService implements ICajaService {

    private final CajaMovimientoRepository cajaRepo;
    private final DeudaProveedorRepository deudaRepo;
    private final ConfiguracionSueldoRepository configSueldoRepo;
    private final AuditoriaService auditoria;

    public ResumenCajasResponse obtenerResumen() {
        BigDecimal saldoFisica = cajaRepo.calcularSaldo(TipoCaja.FISICA);
        BigDecimal saldoBancaria = cajaRepo.calcularSaldo(TipoCaja.BANCARIA);
        BigDecimal saldoComp = cajaRepo.calcularSaldo(TipoCaja.COMPENSACION);
        BigDecimal deudaProveedores = deudaRepo.sumTotalDeudaPendiente();

        BigDecimal sueldosPendientes = configSueldoRepo.totalDevengado();
        if (sueldosPendientes == null) sueldosPendientes = BigDecimal.ZERO;

        List<DescuadreCompensacionResponse> descuadres = detectarDescuadresCompensacion();

        List<String> alertas = new ArrayList<>();
        if (saldoFisica.compareTo(BigDecimal.ZERO) < 0)
            alertas.add("Caja fisica en negativo: $" + saldoFisica);
        if (saldoBancaria.compareTo(BigDecimal.ZERO) < 0)
            alertas.add("Caja bancaria en negativo: $" + saldoBancaria);
        if (!descuadres.isEmpty())
            alertas.add("Caja compensacion desbalanceada: $" + saldoComp + " (" + descuadres.size()
                + " triangulado(s) incompleto(s))");
        if (sueldosPendientes.compareTo(BigDecimal.ZERO) > 0)
            alertas.add("Sueldos devengados a pagar: $" + sueldosPendientes);
        if (deudaProveedores.compareTo(BigDecimal.ZERO) > 0)
            alertas.add("Deuda total proveedores: $" + deudaProveedores);

        return new ResumenCajasResponse(saldoFisica, saldoBancaria, saldoComp, deudaProveedores, sueldosPendientes,
            alertas, descuadres);
    }

    /**
     * Cada triangulado registra un ingreso y un egreso equivalentes en Caja
     * Compensación, con la misma {@code referencia} (idOperacion). Un
     * triangulado completo siempre neta $0; si una referencia no lo hace, le
     * falta la otra mitad del par — eso es lo que se reporta acá.
     */
    private List<DescuadreCompensacionResponse> detectarDescuadresCompensacion() {
        List<CajaMovimiento> movs = cajaRepo.findByTipoCajaOrderByFechaMovimientoDesc(TipoCaja.COMPENSACION);

        Map<String, List<CajaMovimiento>> porReferencia = movs.stream()
            .filter(m -> m.getReferencia() != null && !m.getReferencia().isBlank())
            .collect(Collectors.groupingBy(CajaMovimiento::getReferencia));

        List<DescuadreCompensacionResponse> descuadres = new ArrayList<>();
        for (Map.Entry<String, List<CajaMovimiento>> entry : porReferencia.entrySet()) {
            agregarSiDescuadra(descuadres, entry.getKey(), entry.getValue());
        }

        List<CajaMovimiento> sinReferencia = movs.stream()
            .filter(m -> m.getReferencia() == null || m.getReferencia().isBlank())
            .toList();
        agregarSiDescuadra(descuadres, null, sinReferencia);

        return descuadres;
    }

    private void agregarSiDescuadra(List<DescuadreCompensacionResponse> descuadres, String referencia,
                                     List<CajaMovimiento> movs) {
        if (movs.isEmpty()) return;
        BigDecimal neto = movs.stream()
            .map(m -> m.getTipo() == TipoMovimientoCaja.INGRESO ? m.getMonto() : m.getMonto().negate())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (neto.compareTo(BigDecimal.ZERO) == 0) return;

        CajaMovimiento ultimo = movs.stream()
            .max(Comparator.comparing(CajaMovimiento::getFechaCreacion))
            .orElseThrow();
        descuadres.add(new DescuadreCompensacionResponse(
            referencia, neto, ultimo.getConcepto(), ultimo.getFechaMovimiento()));
    }

    public List<CajaMovimientoResponse> listarMovimientosByCaja(TipoCaja tipoCaja) {
        return cajaRepo.findByTipoCajaOrderByFechaMovimientoDesc(tipoCaja).stream()
            .map(CajaMovimientoResponse::from)
            .toList();
    }

    public List<CajaMovimientoResponse> listarMovimientosByPeriodo(LocalDate desde, LocalDate hasta) {
        return cajaRepo.findByFechaMovimientoBetweenOrderByFechaMovimientoDesc(desde, hasta).stream()
            .map(CajaMovimientoResponse::from)
            .toList();
    }

    @Transactional
    public CajaMovimientoResponse registrarMovimiento(CajaMovimientoRequest req, String creadoPor) {
        CajaMovimiento mov = CajaMovimiento.builder()
            .tipo(req.tipo())
            .tipoCaja(req.tipoCaja())
            .concepto(req.concepto().trim())
            .monto(req.monto())
            .referencia(req.referencia())
            .fechaMovimiento(req.fechaMovimiento() != null ? req.fechaMovimiento() : LocalDate.now())
            .creadoPor(creadoPor)
            .build();
        CajaMovimientoResponse resp = CajaMovimientoResponse.from(cajaRepo.save(mov));
        auditoria.registrar(CurrentUser.usernameOrSistema(), "CAJA", "Movimiento de caja manual",
                "Caja " + req.tipoCaja(),
                req.tipo() + " $" + req.monto() + " · " + req.concepto().trim());
        return resp;
    }
}
