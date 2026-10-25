package com.gs.monolito.finanzas.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.finanzas.dto.ConfirmarRegistroRequest;
import com.gs.monolito.finanzas.dto.PagoAutomaticoRequest;
import com.gs.monolito.finanzas.dto.RegistroPagoBotResponse;
import com.gs.monolito.finanzas.exception.BusinessException;
import com.gs.monolito.finanzas.model.CajaMovimiento;
import com.gs.monolito.finanzas.model.Comprobante;
import com.gs.monolito.finanzas.model.ConfiguracionSueldo;
import com.gs.monolito.finanzas.model.EstadoPago;
import com.gs.monolito.finanzas.model.EstadoRegistroBot;
import com.gs.monolito.finanzas.model.FuentePago;
import com.gs.monolito.finanzas.model.RegistroPagoBot;
import com.gs.monolito.finanzas.model.TipoCaja;
import com.gs.monolito.finanzas.model.TipoMovimientoCaja;
import com.gs.monolito.finanzas.repository.CajaMovimientoRepository;
import com.gs.monolito.finanzas.repository.ComprobanteRepository;
import com.gs.monolito.finanzas.repository.ConfiguracionSueldoRepository;
import com.gs.monolito.finanzas.repository.DeudaProveedorRepository;
import com.gs.monolito.finanzas.repository.PagoSueldoRepository;
import com.gs.monolito.finanzas.repository.ProveedorRepository;
import com.gs.monolito.finanzas.repository.RegistroPagoBotRepository;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S8/S9: un comprobante nunca descuenta la deuda de un odontólogo "porque comparten una palabra". Solo se da por
 * resuelto cuando el nombre del emisor coincide EXACTO con un único odontólogo con deuda; si coincide en parte o
 * hay dos con el mismo nombre, el pago NO se aplica solo: queda pendiente con los candidatos y lo decide una persona.
 */
@ExtendWith(MockitoExtension.class)
class GestionSueldoQuienPagoTest {

    @Mock private ConfiguracionSueldoRepository configRepo;
    @Mock private PagoSueldoRepository pagoRepo;
    @Mock private ProveedorRepository proveedorRepo;
    @Mock private RegistroPagoBotRepository registroRepo;
    @Mock private ComprobanteRepository comprobanteRepo;
    @Mock private DeudaProveedorRepository deudaProveedorRepo;
    @Mock private CajaMovimientoRepository cajaRepo;
    @Mock private AuditoriaService auditoria;
    @Mock private RevisionPago revision;
    @Mock private LibroSueldos libro;
    @Mock private DevengoService devengo;
    @Mock private FinanzasComprobanteStorageService minio;
    @InjectMocks private GestionSueldoService service;

    private Comprobante deudaJuanPerez;
    private Comprobante deudaJuanCarlosGomez;

    private Comprobante deuda(Long id, Long odontologoId, String nombre, String monto) {
        return Comprobante.builder().id(id).nroComprobante("COMP-" + id).pedidoId(id).nroPedido("PED-" + id)
                .odontologoId(odontologoId).odontologoNombre(nombre).trabajo("Corona")
                .monto(new BigDecimal(monto)).montoPagado(BigDecimal.ZERO).estadoPago(EstadoPago.PENDIENTE)
                .fechaEmision(LocalDate.of(2026, 10, 1)).build();
    }

    @BeforeEach
    void preparar() {
        deudaJuanPerez = deuda(1L, 10L, "Juan Pérez", "100000");
        deudaJuanCarlosGomez = deuda(2L, 20L, "Juan Carlos Gómez", "50000");
        lenient().when(registroRepo.save(any(RegistroPagoBot.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(comprobanteRepo.findByOdontologoIdAndEstadoPagoIn(10L, List.of(EstadoPago.PENDIENTE, EstadoPago.PARCIAL)))
                .thenReturn(List.of(deudaJuanPerez));
        lenient().when(comprobanteRepo.findByOdontologoIdAndEstadoPagoIn(20L, List.of(EstadoPago.PENDIENTE, EstadoPago.PARCIAL)))
                .thenReturn(List.of(deudaJuanCarlosGomez));
        lenient().when(configRepo.findAllByOrderByEmpleadoNombreAsc()).thenReturn(List.of(lucia()));
    }

    private ConfiguracionSueldo lucia() {
        return ConfiguracionSueldo.builder().empleadoId(5L).empleadoNombre("Lucía Benítez").activo(true)
                .saldoDevengado(new BigDecimal("80000")).saldoSobrante(BigDecimal.ZERO).build();
    }

    private void hayDeudas(Comprobante... cs) {
        when(comprobanteRepo.findByEstadoPagoIn(List.of(EstadoPago.PENDIENTE, EstadoPago.PARCIAL))).thenReturn(List.of(cs));
    }

    private PagoAutomaticoRequest transferenciaA(String emisor) {
        PagoAutomaticoRequest r = new PagoAutomaticoRequest();
        r.setReceptorNombre("Lucía Benítez");
        r.setEmisor(emisor);
        r.setMonto(new BigDecimal("60000"));
        r.setIdOperacion("OP-" + emisor.hashCode());
        r.setIdMensaje("msg-" + emisor.hashCode());
        return r;
    }

    private RegistroPagoBot pendiente(String emisor) {
        return RegistroPagoBot.builder().id(77L).estado(EstadoRegistroBot.PENDIENTE).fuente(FuentePago.TRANSFERENCIA)
                .monto(new BigDecimal("60000")).emisor(emisor).receptorNombre("Lucía Benítez").idOperacion("OP-77").build();
    }

    private ConfirmarRegistroRequest elegir(Long odontologoId) {
        ConfirmarRegistroRequest r = new ConfirmarRegistroRequest();
        r.setOdontologoId(odontologoId);
        return r;
    }

    private List<CajaMovimiento> movimientosDeCaja() {
        ArgumentCaptor<CajaMovimiento> c = ArgumentCaptor.forClass(CajaMovimiento.class);
        verify(cajaRepo, org.mockito.Mockito.atLeast(0)).save(c.capture());
        return c.getAllValues();
    }

    // ── el caso de S8 ────────────────────────────────────────────────

    @Test
    void unPagoDeJuanPerezSaldaSoloLaDeudaDeJuanPerez_nuncaLaDeJuanCarlosGomez() {
        hayDeudas(deudaJuanPerez, deudaJuanCarlosGomez);

        RegistroPagoBotResponse r = service.registrarPagoAutomatico(transferenciaA("Juan Pérez"));

        assertThat(r.estado()).isEqualTo(EstadoRegistroBot.REGISTRADO);
        assertThat(deudaJuanPerez.getMontoPagado()).isEqualByComparingTo("60000");
        assertThat(deudaJuanCarlosGomez.getMontoPagado()).isEqualByComparingTo("0");
        verify(comprobanteRepo, never()).save(deudaJuanCarlosGomez);
        assertThat(r.mensaje()).startsWith("Pago directo de Juan Pérez");
    }

    @Test
    void unEmisorQueSoloComparteUnaPalabraNoDescuentaLaDeudaDeNadie_esUnPagoNormalDelLaboratorio() {
        hayDeudas(deudaJuanPerez, deudaJuanCarlosGomez);

        RegistroPagoBotResponse r = service.registrarPagoAutomatico(transferenciaA("Juan Martínez"));

        assertThat(r.estado()).isEqualTo(EstadoRegistroBot.REGISTRADO);
        assertThat(deudaJuanPerez.getMontoPagado()).isEqualByComparingTo("0");
        assertThat(deudaJuanCarlosGomez.getMontoPagado()).isEqualByComparingTo("0");
        assertThat(movimientosDeCaja()).extracting(CajaMovimiento::getTipoCaja).containsOnly(TipoCaja.BANCARIA);
    }

    @Test
    void unaSolaPalabraDelEmisorNoAlcanza() {
        hayDeudas(deudaJuanPerez, deudaJuanCarlosGomez);

        service.registrarPagoAutomatico(transferenciaA("Juan"));

        assertThat(deudaJuanPerez.getMontoPagado()).isEqualByComparingTo("0");
        assertThat(deudaJuanCarlosGomez.getMontoPagado()).isEqualByComparingTo("0");
    }

    // ── cuando no se sabe seguro: lo decide una persona ───────────────

    @Test
    void siElEmisorCoincideSoloEnParte_noSePagaSoloYQuedaPendienteConElCandidato() {
        hayDeudas(deudaJuanPerez, deudaJuanCarlosGomez);

        RegistroPagoBotResponse r = service.registrarPagoAutomatico(transferenciaA("Pérez Juan Carlos"));

        assertThat(r.estado()).isEqualTo(EstadoRegistroBot.PENDIENTE);
        assertThat(r.motivosRevision()).contains("No se sabe seguro quién pagó").contains("Juan Pérez");
        verify(pagoRepo, never()).save(any());
        assertThat(deudaJuanPerez.getMontoPagado()).isEqualByComparingTo("0");
    }

    @Test
    void dosOdontologosConElMismoNombreNoSeResuelvenSolos() {
        Comprobante otroJuanPerez = deuda(3L, 30L, "Juan Pérez", "20000");
        hayDeudas(deudaJuanPerez, otroJuanPerez);

        RegistroPagoBotResponse r = service.registrarPagoAutomatico(transferenciaA("Juan Pérez"));

        assertThat(r.estado()).isEqualTo(EstadoRegistroBot.PENDIENTE);
        assertThat(r.motivosRevision()).contains("Juan Pérez");
        assertThat(deudaJuanPerez.getMontoPagado()).isEqualByComparingTo("0");
        assertThat(otroJuanPerez.getMontoPagado()).isEqualByComparingTo("0");
    }

    @Test
    void elPendienteTraeLosCandidatosParaQueQuienApruebaElija() {
        hayDeudas(deudaJuanPerez, deudaJuanCarlosGomez);
        when(registroRepo.findByEstadoAndFuenteOrderByFechaHoraDesc(EstadoRegistroBot.PENDIENTE, FuentePago.TRANSFERENCIA))
                .thenReturn(List.of(pendiente("Pérez Juan Carlos")));

        List<RegistroPagoBotResponse> pendientes = service.listarPendientesRevision();

        assertThat(pendientes).hasSize(1);
        assertThat(pendientes.get(0).candidatosEmisor()).extracting(c -> c.odontologoId()).containsExactly(10L);
        assertThat(pendientes.get(0).candidatosEmisor().get(0).deuda()).isEqualByComparingTo("100000");
    }

    @Test
    void sinDuda_elPendienteNoPideElegirNada() {
        hayDeudas(deudaJuanPerez, deudaJuanCarlosGomez);
        when(registroRepo.findByEstadoAndFuenteOrderByFechaHoraDesc(EstadoRegistroBot.PENDIENTE, FuentePago.TRANSFERENCIA))
                .thenReturn(List.of(pendiente("Juan Pérez")));

        assertThat(service.listarPendientesRevision().get(0).candidatosEmisor()).isEmpty();
    }

    // ── al aprobar ────────────────────────────────────────────────────

    @Test
    void aprobarSinElegirCuandoNoSeSabeQuienPago_noAplicaNadaYPideElegir() {
        hayDeudas(deudaJuanPerez, deudaJuanCarlosGomez);
        when(registroRepo.findById(77L)).thenReturn(Optional.of(pendiente("Pérez Juan Carlos")));

        assertThatThrownBy(() -> service.confirmarEfectivo(77L, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No se sabe quién pagó").hasMessageContaining("Juan Pérez");
        verify(pagoRepo, never()).save(any());
        assertThat(deudaJuanPerez.getMontoPagado()).isEqualByComparingTo("0");
    }

    @Test
    void aprobarElegiendoAlOdontologo_lePagaSoloAEseYDejaElPagoDirectoEnCompensacion() {
        hayDeudas(deudaJuanPerez, deudaJuanCarlosGomez);
        when(registroRepo.findById(77L)).thenReturn(Optional.of(pendiente("Pérez Juan Carlos")));

        RegistroPagoBotResponse r = service.confirmarEfectivo(77L, elegir(10L));

        assertThat(r.estado()).isEqualTo(EstadoRegistroBot.REGISTRADO);
        assertThat(deudaJuanPerez.getMontoPagado()).isEqualByComparingTo("60000");
        assertThat(deudaJuanCarlosGomez.getMontoPagado()).isEqualByComparingTo("0");
        assertThat(movimientosDeCaja()).extracting(CajaMovimiento::getTipoCaja).containsOnly(TipoCaja.COMPENSACION);
        assertThat(movimientosDeCaja()).extracting(CajaMovimiento::getTipo)
                .containsExactlyInAnyOrder(TipoMovimientoCaja.INGRESO, TipoMovimientoCaja.EGRESO);
    }

    @Test
    void aprobarDiciendoQueNoFueUnOdontologo_esUnPagoNormalDelLaboratorioYNoTocaLasDeudas() {
        // no hace falta ni mirar las deudas: quien aprueba ya dijo que no fue un odontólogo
        when(registroRepo.findById(77L)).thenReturn(Optional.of(pendiente("Pérez Juan Carlos")));
        ConfirmarRegistroRequest ninguno = new ConfirmarRegistroRequest();
        ninguno.setSinOdontologo(true);

        RegistroPagoBotResponse r = service.confirmarEfectivo(77L, ninguno);

        assertThat(r.estado()).isEqualTo(EstadoRegistroBot.REGISTRADO);
        assertThat(deudaJuanPerez.getMontoPagado()).isEqualByComparingTo("0");
        assertThat(movimientosDeCaja()).extracting(CajaMovimiento::getTipoCaja).containsOnly(TipoCaja.BANCARIA);
    }

    @Test
    void noSePuedeElegirUnOdontologoQueNoEstabaEntreLosPosibles() {
        hayDeudas(deudaJuanPerez, deudaJuanCarlosGomez);
        when(registroRepo.findById(77L)).thenReturn(Optional.of(pendiente("Pérez Juan Carlos")));

        assertThatThrownBy(() -> service.confirmarEfectivo(77L, elegir(20L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no figura entre los posibles");
        assertThat(deudaJuanCarlosGomez.getMontoPagado()).isEqualByComparingTo("0");
        verify(comprobanteRepo, times(0)).save(deudaJuanCarlosGomez);
    }
}
