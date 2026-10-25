package com.gs.monolito.finanzas.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.finanzas.dto.PagoSueldoRequest;
import com.gs.monolito.finanzas.model.CajaMovimiento;
import com.gs.monolito.finanzas.model.ConfiguracionSueldo;
import com.gs.monolito.finanzas.model.ManejoSobrante;
import com.gs.monolito.finanzas.model.MedioPago;
import com.gs.monolito.finanzas.model.PagoSueldo;
import com.gs.monolito.finanzas.model.TipoCaja;
import com.gs.monolito.finanzas.model.TipoMovimientoCaja;
import com.gs.monolito.finanzas.repository.CajaMovimientoRepository;
import com.gs.monolito.finanzas.repository.ConfiguracionSueldoRepository;
import com.gs.monolito.finanzas.repository.PagoSueldoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * Un sueldo cargado a mano sale de la caja que corresponde al medio con el que se pagó. Antes salía SIEMPRE de la
 * Caja Física, aunque se hubiera pagado por transferencia.
 */
@ExtendWith(MockitoExtension.class)
class GestionSueldoMedioDePagoTest {

    @Mock private ConfiguracionSueldoRepository configRepo;
    @Mock private PagoSueldoRepository pagoRepo;
    @Mock private CajaMovimientoRepository cajaRepo;
    @Mock private AuditoriaService auditoria;
    @Mock private LibroSueldos libro;
    @InjectMocks private GestionSueldoService service;

    @BeforeEach
    void preparar() {
        lenient().when(configRepo.findByEmpleadoId(5L)).thenReturn(Optional.of(ConfiguracionSueldo.builder()
                .empleadoId(5L).empleadoNombre("Lucía Benítez").activo(true)
                .saldoDevengado(new BigDecimal("50000")).saldoSobrante(BigDecimal.ZERO).build()));
        lenient().when(pagoRepo.save(any(PagoSueldo.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private PagoSueldoRequest pago(String monto, MedioPago medio, ManejoSobrante manejo) {
        PagoSueldoRequest r = new PagoSueldoRequest();
        r.setUsuarioId(5L);
        r.setMonto(new BigDecimal(monto));
        r.setMedio(medio);
        r.setManejoSobrante(manejo);
        return r;
    }

    private List<CajaMovimiento> movimientos() {
        ArgumentCaptor<CajaMovimiento> c = ArgumentCaptor.forClass(CajaMovimiento.class);
        verify(cajaRepo, atLeastOnce()).save(c.capture());
        return c.getAllValues();
    }

    @Test
    void unSueldoEnEfectivoSaleDeLaCajaFisica() {
        service.registrarPago(pago("30000", MedioPago.EFECTIVO, ManejoSobrante.DESCONTAR_PROXIMO));

        assertThat(movimientos()).singleElement().satisfies(m -> {
            assertThat(m.getTipoCaja()).isEqualTo(TipoCaja.FISICA);
            assertThat(m.getTipo()).isEqualTo(TipoMovimientoCaja.EGRESO);
            assertThat(m.getConcepto()).isEqualTo("Sueldo (efectivo) a Lucía Benítez");
        });
    }

    @Test
    void unSueldoPorTransferenciaSaleDeLaCajaBancaria_noDeLaFisica() {
        service.registrarPago(pago("30000", MedioPago.TRANSFERENCIA, ManejoSobrante.DESCONTAR_PROXIMO));

        assertThat(movimientos()).singleElement().satisfies(m -> {
            assertThat(m.getTipoCaja()).isEqualTo(TipoCaja.BANCARIA);
            assertThat(m.getConcepto()).isEqualTo("Sueldo (transferencia) a Lucía Benítez");
        });
    }

    @Test
    void sinIndicarElMedioSeAsumeEfectivo_comoAntes() {
        PagoSueldoRequest r = pago("30000", null, ManejoSobrante.DESCONTAR_PROXIMO);

        service.registrarPago(r);

        assertThat(movimientos()).singleElement().extracting(CajaMovimiento::getTipoCaja).isEqualTo(TipoCaja.FISICA);
    }

    @Test
    void elPagoGuardaConQueSePago() {
        service.registrarPago(pago("30000", MedioPago.TRANSFERENCIA, ManejoSobrante.DESCONTAR_PROXIMO));

        ArgumentCaptor<PagoSueldo> c = ArgumentCaptor.forClass(PagoSueldo.class);
        verify(pagoRepo).save(c.capture());
        assertThat(c.getValue().getMedio()).isEqualTo(MedioPago.TRANSFERENCIA);
    }

    @Test
    void siElEmpleadoDevuelveLaDiferencia_laPlataVuelveALaMismaCajaPorDondeSalio() {
        // se le deben $50.000 y se le pagan $60.000 por transferencia: devuelve $10.000
        service.registrarPago(pago("60000", MedioPago.TRANSFERENCIA, ManejoSobrante.DEVUELVE_EMPLEADO));

        List<CajaMovimiento> ms = movimientos();
        assertThat(ms).hasSize(2);
        assertThat(ms).extracting(CajaMovimiento::getTipoCaja).containsOnly(TipoCaja.BANCARIA);
        assertThat(ms).extracting(CajaMovimiento::getTipo)
                .containsExactlyInAnyOrder(TipoMovimientoCaja.EGRESO, TipoMovimientoCaja.INGRESO);
    }
}
