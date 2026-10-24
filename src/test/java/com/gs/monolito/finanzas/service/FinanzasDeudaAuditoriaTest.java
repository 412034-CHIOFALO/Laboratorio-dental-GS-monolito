package com.gs.monolito.finanzas.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.finanzas.dto.ComprobanteRequest;
import com.gs.monolito.finanzas.model.Comprobante;
import com.gs.monolito.finanzas.model.EstadoPago;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Todo lo que mueve la deuda de un odontólogo deja rastro: se genera al entregar, se corrige su monto o se pasa a
 * otro odontólogo. Antes solo quedaban auditados los pagos.
 */
@ExtendWith(MockitoExtension.class)
class FinanzasDeudaAuditoriaTest {

    @Mock private ComprobanteRepository repository;
    @Mock private CajaMovimientoRepository cajaRepo;
    @Mock private PagoCuentaCorrienteRepository pagoRepo;
    @Mock private AuditoriaService auditoria;

    @InjectMocks private FinanzasService finanzas;

    private Comprobante deuda() {
        return Comprobante.builder().id(5L).nroComprobante("COMP-202610-0005").pedidoId(11L).nroPedido("PED-11")
                .odontologoId(1L).odontologoNombre("Dr. Pérez").trabajo("Corona")
                .monto(new BigDecimal("30000")).montoPagado(BigDecimal.ZERO)
                .estadoPago(EstadoPago.PENDIENTE).fechaEmision(LocalDate.of(2026, 10, 20)).build();
    }

    @Test
    void entregarUnPedidoConDeudaQuedaRegistrado_conMontoPedidoYTrabajo() {
        when(repository.findByPedidoId(11L)).thenReturn(Optional.empty());
        lenient().when(repository.save(any(Comprobante.class))).thenAnswer(inv -> inv.getArgument(0));
        ComprobanteRequest req = new ComprobanteRequest();
        req.setPedidoId(11L);
        req.setNroPedido("PED-11");
        req.setOdontologoId(1L);
        req.setOdontologoNombre("Dr. Pérez");
        req.setTrabajo("Corona");
        req.setMonto(new BigDecimal("30000"));
        req.setFechaEmision(LocalDate.of(2026, 10, 20));

        finanzas.emitir(req);

        ArgumentCaptor<String> detalle = ArgumentCaptor.forClass(String.class);
        verify(auditoria).registrar(any(), eq("CREAR"), eq("Deuda del odontólogo generada"), any(), detalle.capture());
        assertThat(detalle.getValue()).isEqualTo("$30000 · pedido PED-11 · Corona");
    }

    @Test
    void siLaDeudaYaEstabaEmitida_noSeRegistraDeNuevo() {
        when(repository.findByPedidoId(11L)).thenReturn(Optional.of(deuda()));
        ComprobanteRequest req = new ComprobanteRequest();
        req.setPedidoId(11L);

        finanzas.emitir(req);

        verify(auditoria, never()).registrar(any(), any(), any(), any(), any());
    }

    @Test
    void corregirElMontoDeLaDeudaQuedaRegistrado_conElAntesYElDespues() {
        when(repository.findByPedidoId(11L)).thenReturn(Optional.of(deuda()));

        finanzas.actualizarMontoPorPedido(11L, new BigDecimal("35000"));

        ArgumentCaptor<String> detalle = ArgumentCaptor.forClass(String.class);
        verify(auditoria).registrar(any(), eq("EDITAR"), eq("Monto de la deuda corregido"), any(), detalle.capture());
        assertThat(detalle.getValue()).isEqualTo("$30000 → $35000 · pedido PED-11");
    }

    @Test
    void guardarElMismoMontoNoGeneraRuido() {
        when(repository.findByPedidoId(11L)).thenReturn(Optional.of(deuda()));

        finanzas.actualizarMontoPorPedido(11L, new BigDecimal("30000"));

        verify(auditoria, never()).registrar(any(), any(), any(), any(), any());
    }

    @Test
    void pasarLaDeudaAOtroOdontologoQuedaRegistrado() {
        when(repository.findByPedidoId(11L)).thenReturn(Optional.of(deuda()));

        boolean reasignada = finanzas.reasignarOdontologoPorPedido(11L, 2L, "Dra. Gómez");

        assertThat(reasignada).isTrue();
        ArgumentCaptor<String> detalle = ArgumentCaptor.forClass(String.class);
        verify(auditoria).registrar(any(), eq("EDITAR"), eq("Deuda reasignada a otro odontólogo"), any(), detalle.capture());
        assertThat(detalle.getValue()).isEqualTo("Dr. Pérez → Dra. Gómez · $30000");
    }

    @Test
    void siLaDeudaTienePagosNoSeReasignaYNoSeRegistraNada() {
        Comprobante conPagos = deuda();
        conPagos.setMontoPagado(new BigDecimal("1000"));
        when(repository.findByPedidoId(11L)).thenReturn(Optional.of(conPagos));

        boolean reasignada = finanzas.reasignarOdontologoPorPedido(11L, 2L, "Dra. Gómez");

        assertThat(reasignada).isFalse();
        verify(auditoria, never()).registrar(any(), any(), any(), any(), any());
    }
}
