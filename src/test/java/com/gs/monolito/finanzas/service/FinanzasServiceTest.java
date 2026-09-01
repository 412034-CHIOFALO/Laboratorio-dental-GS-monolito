package com.gs.monolito.finanzas.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.finanzas.dto.ComprobanteRequest;
import com.gs.monolito.finanzas.dto.ComprobanteResponse;
import com.gs.monolito.finanzas.dto.PagoCuentaCorrienteRequest;
import com.gs.monolito.finanzas.dto.PagoCuentaCorrienteResponse;
import com.gs.monolito.finanzas.exception.BusinessException;
import com.gs.monolito.finanzas.model.CajaMovimiento;
import com.gs.monolito.finanzas.model.Comprobante;
import com.gs.monolito.finanzas.model.EstadoPago;
import com.gs.monolito.finanzas.model.MedioPago;
import com.gs.monolito.finanzas.model.TipoCaja;
import com.gs.monolito.finanzas.repository.CajaMovimientoRepository;
import com.gs.monolito.finanzas.repository.ComprobanteRepository;
import com.gs.monolito.finanzas.repository.PagoCuentaCorrienteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * FIFO real de plata: un pago se imputa a los comprobantes con saldo del
 * odontólogo, más viejo primero, pudiendo cubrir uno o varios parcial o
 * totalmente. Es la lógica financiera más intrincada de todo el monolito —
 * la más peligrosa de romper sin darse cuenta en un refactor futuro.
 */
@ExtendWith(MockitoExtension.class)
class FinanzasServiceTest {

    @Mock private ComprobanteRepository repository;
    @Mock private CajaMovimientoRepository cajaRepo;
    @Mock private PagoCuentaCorrienteRepository pagoRepo;
    @Mock private AuditoriaService auditoria;

    @InjectMocks private FinanzasService finanzasService;

    private Comprobante comprobante(Long id, BigDecimal monto, LocalDate emision, EstadoPago estado) {
        return Comprobante.builder()
                .id(id).nroComprobante("COMP-202601-000" + id)
                .pedidoId(id).nroPedido("PED-" + id)
                .odontologoId(1L).odontologoNombre("Dr. Pérez")
                .trabajo("Corona").monto(monto).montoPagado(BigDecimal.ZERO)
                .estadoPago(estado).fechaEmision(emision)
                .build();
    }

    private void stubGuardados() {
        lenient().when(pagoRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── registrarPagoCuentaCorriente (FIFO) ──────────────────────

    @Test
    void sinDeudasPendientes_rechazaConBusinessException() {
        when(repository.findByOdontologoIdAndEstadoPagoIn(eq(1L), any())).thenReturn(List.of());

        PagoCuentaCorrienteRequest req = new PagoCuentaCorrienteRequest(new BigDecimal("1000"), MedioPago.EFECTIVO, null, null);

        assertThatThrownBy(() -> finanzasService.registrarPagoCuentaCorriente(1L, req))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void pagoQueCubreElComprobanteCompleto_quedaCobrado() {
        stubGuardados();
        Comprobante c = comprobante(1L, new BigDecimal("10000"), LocalDate.now().minusDays(5), EstadoPago.PENDIENTE);
        when(repository.findByOdontologoIdAndEstadoPagoIn(eq(1L), any())).thenReturn(List.of(c));
        when(repository.sumMontosPendientesByOdontologo(1L)).thenReturn(BigDecimal.ZERO);

        PagoCuentaCorrienteRequest req = new PagoCuentaCorrienteRequest(new BigDecimal("10000"), MedioPago.EFECTIVO, null, "pago completo");
        PagoCuentaCorrienteResponse resp = finanzasService.registrarPagoCuentaCorriente(1L, req);

        assertThat(c.getEstadoPago()).isEqualTo(EstadoPago.COBRADO);
        assertThat(c.getMontoPagado()).isEqualByComparingTo("10000");
        assertThat(resp.montoImputado()).isEqualByComparingTo("10000");

        ArgumentCaptor<CajaMovimiento> cajaCaptor = ArgumentCaptor.forClass(CajaMovimiento.class);
        verify(cajaRepo).save(cajaCaptor.capture());
        assertThat(cajaCaptor.getValue().getTipoCaja()).isEqualTo(TipoCaja.FISICA);
        assertThat(cajaCaptor.getValue().getMonto()).isEqualByComparingTo("10000");
    }

    @Test
    void pagoParcial_dejaElComprobanteEnEstadoParcial() {
        stubGuardados();
        Comprobante c = comprobante(1L, new BigDecimal("10000"), LocalDate.now(), EstadoPago.PENDIENTE);
        when(repository.findByOdontologoIdAndEstadoPagoIn(eq(1L), any())).thenReturn(List.of(c));
        when(repository.sumMontosPendientesByOdontologo(1L)).thenReturn(new BigDecimal("6000"));

        PagoCuentaCorrienteRequest req = new PagoCuentaCorrienteRequest(new BigDecimal("4000"), MedioPago.TRANSFERENCIA, null, null);
        finanzasService.registrarPagoCuentaCorriente(1L, req);

        assertThat(c.getEstadoPago()).isEqualTo(EstadoPago.PARCIAL);
        assertThat(c.getMontoPagado()).isEqualByComparingTo("4000");
    }

    @Test
    void pagoPorTransferencia_entraACajaBancaria_yEfectivoACajaFisica() {
        stubGuardados();
        Comprobante c = comprobante(1L, new BigDecimal("5000"), LocalDate.now(), EstadoPago.PENDIENTE);
        when(repository.findByOdontologoIdAndEstadoPagoIn(eq(1L), any())).thenReturn(List.of(c));
        when(repository.sumMontosPendientesByOdontologo(1L)).thenReturn(BigDecimal.ZERO);

        PagoCuentaCorrienteRequest req = new PagoCuentaCorrienteRequest(new BigDecimal("5000"), MedioPago.TRANSFERENCIA, null, null);
        finanzasService.registrarPagoCuentaCorriente(1L, req);

        ArgumentCaptor<CajaMovimiento> cajaCaptor = ArgumentCaptor.forClass(CajaMovimiento.class);
        verify(cajaRepo).save(cajaCaptor.capture());
        assertThat(cajaCaptor.getValue().getTipoCaja()).isEqualTo(TipoCaja.BANCARIA);
    }

    @Test
    void imputaFIFO_alComprobanteMasViejoPrimero_sinImportarElOrdenDelRepo() {
        stubGuardados();
        Comprobante viejo = comprobante(1L, new BigDecimal("5000"), LocalDate.now().minusDays(30), EstadoPago.PENDIENTE);
        Comprobante nuevo = comprobante(2L, new BigDecimal("5000"), LocalDate.now().minusDays(1), EstadoPago.PENDIENTE);
        // A propósito en orden "nuevo, viejo" — el service es el que tiene que ordenar por fecha, no confiar en el repo.
        when(repository.findByOdontologoIdAndEstadoPagoIn(eq(1L), any())).thenReturn(List.of(nuevo, viejo));
        when(repository.sumMontosPendientesByOdontologo(1L)).thenReturn(new BigDecimal("5000"));

        PagoCuentaCorrienteRequest req = new PagoCuentaCorrienteRequest(new BigDecimal("5000"), MedioPago.EFECTIVO, null, null);
        finanzasService.registrarPagoCuentaCorriente(1L, req);

        assertThat(viejo.getEstadoPago()).isEqualTo(EstadoPago.COBRADO);
        assertThat(nuevo.getEstadoPago()).isEqualTo(EstadoPago.PENDIENTE);
    }

    @Test
    void conExcedenteSobreLaDeudaTotal_avisaQueNoSeImputaComoSaldoAFavor() {
        stubGuardados();
        Comprobante c = comprobante(1L, new BigDecimal("3000"), LocalDate.now(), EstadoPago.PENDIENTE);
        when(repository.findByOdontologoIdAndEstadoPagoIn(eq(1L), any())).thenReturn(List.of(c));
        when(repository.sumMontosPendientesByOdontologo(1L)).thenReturn(BigDecimal.ZERO);

        PagoCuentaCorrienteRequest req = new PagoCuentaCorrienteRequest(new BigDecimal("5000"), MedioPago.EFECTIVO, null, null);
        PagoCuentaCorrienteResponse resp = finanzasService.registrarPagoCuentaCorriente(1L, req);

        assertThat(resp.montoImputado()).isEqualByComparingTo("3000");
        assertThat(resp.mensaje()).contains("Excedente no imputado");
    }

    // ── emitir (idempotencia de la deuda) ────────────────────────

    @Test
    void emitir_siYaExisteComprobanteParaElPedido_devuelveElExistenteSinDuplicar() {
        Comprobante existente = comprobante(1L, new BigDecimal("15000"), LocalDate.now(), EstadoPago.PENDIENTE);
        when(repository.findByPedidoId(1L)).thenReturn(Optional.of(existente));

        ComprobanteRequest req = new ComprobanteRequest();
        req.setPedidoId(1L);

        ComprobanteResponse resp = finanzasService.emitir(req);

        assertThat(resp.id()).isEqualTo(1L);
        verify(repository, never()).save(any());
    }

    // ── registrarCobro ────────────────────────────────────────────

    @Test
    void registrarCobro_siYaEstabaCobrado_rechazaConBusinessException() {
        Comprobante c = comprobante(1L, new BigDecimal("1000"), LocalDate.now(), EstadoPago.COBRADO);
        when(repository.findById(1L)).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> finanzasService.registrarCobro(1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void registrarCobro_marcaElComprobanteComoCobradoPorElMontoTotal() {
        Comprobante c = comprobante(1L, new BigDecimal("8000"), LocalDate.now(), EstadoPago.PENDIENTE);
        when(repository.findById(1L)).thenReturn(Optional.of(c));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        finanzasService.registrarCobro(1L);

        assertThat(c.getEstadoPago()).isEqualTo(EstadoPago.COBRADO);
        assertThat(c.getMontoPagado()).isEqualByComparingTo("8000");
    }
}
