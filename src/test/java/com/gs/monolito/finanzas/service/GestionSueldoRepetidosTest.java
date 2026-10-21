package com.gs.monolito.finanzas.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.finanzas.dto.PagoAutomaticoRequest;
import com.gs.monolito.finanzas.dto.PagoEfectivoRequest;
import com.gs.monolito.finanzas.dto.RegistroPagoBotResponse;
import com.gs.monolito.finanzas.exception.BusinessException;
import com.gs.monolito.finanzas.model.ConfiguracionSueldo;
import com.gs.monolito.finanzas.model.EstadoRegistroBot;
import com.gs.monolito.finanzas.model.FrecuenciaPago;
import com.gs.monolito.finanzas.model.FuentePago;
import com.gs.monolito.finanzas.model.RegistroPagoBot;
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
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "No se acepta automáticamente dos veces el mismo comprobante". Cubre el hueco que había: se miraba solo contra lo
 * ya registrado, así que un comprobante reenviado mientras el primero esperaba revisión también quedaba pendiente, y
 * aprobando los dos se pagaba doble.
 */
@ExtendWith(MockitoExtension.class)
class GestionSueldoRepetidosTest {

    @Mock private RegistroPagoBotRepository registroRepo;
    @Mock private ConfiguracionSueldoRepository configRepo;
    @Mock private ProveedorRepository proveedorRepo;
    @Mock private PagoSueldoRepository pagoRepo;
    @Mock private ComprobanteRepository comprobanteRepo;
    @Mock private DeudaProveedorRepository deudaProveedorRepo;
    @Mock private CajaMovimientoRepository cajaMovimientoRepo;
    @Mock private AuditoriaService auditoria;
    @Mock private RevisionPago revision;   // sin stub: lista vacía = el semáforo lo daría por bueno
    @Mock private DevengoService devengo;
    @Mock private LibroSueldos libro;
    @InjectMocks private GestionSueldoService service;

    private static final BigDecimal MONTO = new BigDecimal("85000");

    @BeforeEach
    void guardarDevuelveLoMismo() {
        lenient().when(registroRepo.save(any(RegistroPagoBot.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private PagoAutomaticoRequest transferencia(String idMensaje, String idOperacion, String archivo) {
        PagoAutomaticoRequest req = new PagoAutomaticoRequest();
        req.setReceptorNombre("Carlos López");
        req.setMonto(MONTO);
        req.setEmisor("Dr. García");
        req.setIdMensaje(idMensaje);
        req.setIdOperacion(idOperacion);
        if (archivo != null) {
            req.setComprobanteBase64(Base64.getEncoder().encodeToString(archivo.getBytes()));
            req.setComprobanteMime("application/pdf");
            req.setComprobanteNombre("comprobante.pdf");
        }
        return req;
    }

    private void hayUnEmpleado() {
        ConfiguracionSueldo c = ConfiguracionSueldo.builder().empleadoId(2L).empleadoNombre("Carlos López")
                .frecuencia(FrecuenciaPago.MENSUAL).montoBase(new BigDecimal("300000")).fechaAlta(java.time.LocalDate.of(2026, 1, 1))
                .activo(true).build();
        c.setSaldoDevengado(new BigDecimal("200000"));
        when(configRepo.findAllByOrderByEmpleadoNombreAsc()).thenReturn(List.of(c));
    }

    private static RegistroPagoBot registro(long id, EstadoRegistroBot estado, FuentePago fuente, String receptor, LocalDateTime cuando) {
        return RegistroPagoBot.builder().id(id).estado(estado).fuente(fuente).receptorNombre(receptor)
                .monto(MONTO).fechaHora(cuando).build();
    }

    // ── Al llegar ────────────────────────────────────────────────────

    @Test
    void unComprobanteIgualAUnoQueEsperaRevisionSeMarcaDuplicadoYNoSePaga() {
        when(registroRepo.findFirstByIdOperacionInAndEstadoInAndIdNotOrderByIdAsc(anyCollection(), anyCollection(), anyLong()))
                .thenReturn(Optional.of(registro(8L, EstadoRegistroBot.PENDIENTE, FuentePago.TRANSFERENCIA, "Carlos López", LocalDateTime.now())));

        RegistroPagoBotResponse r = service.registrarPagoAutomatico(transferencia("m2", "98765432101", null));

        assertThat(r.estado()).isEqualTo(EstadoRegistroBot.DUPLICADO);
        assertThat(r.mensaje()).contains("#8").contains("pendiente de revisión").contains("98765432101");
        verify(pagoRepo, never()).save(any());
        verify(libro, never()).registrar(any(), any(), any(), any(), any());
    }

    @Test
    void elMismoArchivoQueUnoPendienteTambienEsDuplicado() {
        when(registroRepo.findFirstByHashComprobanteAndEstadoInAndIdNotOrderByIdAsc(anyString(), anyCollection(), anyLong()))
                .thenReturn(Optional.of(registro(9L, EstadoRegistroBot.PENDIENTE, FuentePago.TRANSFERENCIA, "Carlos López", LocalDateTime.now())));

        RegistroPagoBotResponse r = service.registrarPagoAutomatico(transferencia("m3", null, "pdf-1"));

        assertThat(r.estado()).isEqualTo(EstadoRegistroBot.DUPLICADO);
        assertThat(r.mensaje()).contains("mismo archivo").contains("#9");
    }

    @Test
    @SuppressWarnings("unchecked")
    void elNumeroDeOperacionSeComparaSinEspaciosNiSignosNiMayusculas() {
        service.registrarPagoAutomatico(transferencia("m4", "ab 12-345 678", null));

        ArgumentCaptor<Collection<String>> variantes = ArgumentCaptor.forClass(Collection.class);
        verify(registroRepo).findFirstByIdOperacionInAndEstadoInAndIdNotOrderByIdAsc(variantes.capture(), anyCollection(), eq(0L));
        // se busca tanto lo leído como lo normalizado: cubre registros guardados antes de normalizar
        assertThat(variantes.getValue()).containsExactlyInAnyOrder("ab 12-345 678", "AB12345678");
        ArgumentCaptor<RegistroPagoBot> guardado = ArgumentCaptor.forClass(RegistroPagoBot.class);
        verify(registroRepo).save(guardado.capture());
        assertThat(guardado.getValue().getIdOperacion()).isEqualTo("AB12345678");
    }

    @Test
    void normalizarOperacionDejaSoloLetrasYNumeros() {
        assertThat(GestionSueldoService.normalizarOperacion(" 12 345-678 ")).isEqualTo("12345678");
        assertThat(GestionSueldoService.normalizarOperacion("n° ab.9")).isEqualTo("NAB9");
        assertThat(GestionSueldoService.normalizarOperacion("---")).isNull();
        assertThat(GestionSueldoService.normalizarOperacion(null)).isNull();
    }

    @Test
    void mismoMontoYReceptorCargadosHaceHorasVanARevisionAunqueLaOperacionDifiera() {
        hayUnEmpleado();
        when(registroRepo.findByMontoAndEstadoInAndFechaHoraAfterAndIdNot(eq(MONTO), anyCollection(), any(LocalDateTime.class), anyLong()))
                .thenReturn(List.of(registro(12L, EstadoRegistroBot.REGISTRADO, FuentePago.TRANSFERENCIA, "carlos lopez", LocalDateTime.now().minusHours(2))));

        RegistroPagoBotResponse r = service.registrarPagoAutomatico(transferencia("m5", "OTRA-OPERACION-999", null));

        assertThat(r.estado()).isEqualTo(EstadoRegistroBot.PENDIENTE);
        assertThat(r.motivosRevision()).contains("Se parece a otro comprobante").contains("#12").contains("hace 2 h");
        verify(pagoRepo, never()).save(any());
    }

    @Test
    void siElReceptorOElTipoDePagoNoCoincidenNoSeConsideraParecido() {
        hayUnEmpleado();
        when(registroRepo.findByMontoAndEstadoInAndFechaHoraAfterAndIdNot(eq(MONTO), anyCollection(), any(LocalDateTime.class), anyLong()))
                .thenReturn(List.of(
                        registro(13L, EstadoRegistroBot.REGISTRADO, FuentePago.TRANSFERENCIA, "Pedro Gómez", LocalDateTime.now().minusHours(1)),
                        registro(14L, EstadoRegistroBot.PENDIENTE, FuentePago.EFECTIVO, "Carlos López", LocalDateTime.now().minusHours(1))));

        RegistroPagoBotResponse r = service.registrarPagoAutomatico(transferencia("m6", "OP-DISTINTA-1", null));

        assertThat(r.estado()).isEqualTo(EstadoRegistroBot.REGISTRADO);
    }

    @Test
    void sinNingunParecidoNiRepetidoLaTransferenciaLimpiaSeRegistra() {
        hayUnEmpleado();

        RegistroPagoBotResponse r = service.registrarPagoAutomatico(transferencia("m7", "OP-NUEVA-123", "pdf-nuevo"));

        assertThat(r.estado()).isEqualTo(EstadoRegistroBot.REGISTRADO);
        verify(pagoRepo).save(any());
    }

    // ── Al aprobar ───────────────────────────────────────────────────

    private RegistroPagoBot pendienteParaAprobar(long id, String operacion) {
        RegistroPagoBot r = RegistroPagoBot.builder().id(id).estado(EstadoRegistroBot.PENDIENTE).fuente(FuentePago.TRANSFERENCIA)
                .receptorNombre("Carlos López").emisor("Dr. García").monto(MONTO).idOperacion(operacion)
                .motivosRevision("No coinciden los nombres").build();
        when(registroRepo.findById(id)).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    void alAprobarSeVuelveAMirarSiYaSeAplicoUnoIgualYNoSePagaDeNuevo() {
        RegistroPagoBot pendiente = pendienteParaAprobar(20L, "OP-ABC-123");
        when(registroRepo.findFirstByIdOperacionInAndEstadoInAndIdNotOrderByIdAsc(anyCollection(), eq(List.of(EstadoRegistroBot.REGISTRADO)), eq(20L)))
                .thenReturn(Optional.of(registro(19L, EstadoRegistroBot.REGISTRADO, FuentePago.TRANSFERENCIA, "Carlos López", LocalDateTime.now())));

        assertThatThrownBy(() -> service.confirmarEfectivo(20L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No se puede aprobar").hasMessageContaining("#19").hasMessageContaining("repetido");

        assertThat(pendiente.getEstado()).isEqualTo(EstadoRegistroBot.PENDIENTE);
        verify(pagoRepo, never()).save(any());
    }

    @Test
    void sinOtroIgualYaAplicadoLaAprobacionPaga() {
        hayUnEmpleado();
        RegistroPagoBot pendiente = pendienteParaAprobar(21L, "OP-ABC-124");

        RegistroPagoBotResponse r = service.confirmarEfectivo(21L);

        assertThat(r.estado()).isEqualTo(EstadoRegistroBot.REGISTRADO);
        assertThat(pendiente.getEstado()).isEqualTo(EstadoRegistroBot.REGISTRADO);
        verify(pagoRepo).save(any());
    }

    @Test
    void dosPendientesIgualesSoloSePuedenAprobarUnoDeLosDos() {
        hayUnEmpleado();
        RegistroPagoBot primero = pendienteParaAprobar(40L, "OP-DOBLE-1");
        RegistroPagoBot segundo = pendienteParaAprobar(41L, "OP-DOBLE-1");
        // al aprobar el primero todavía no hay ninguno aplicado; al aprobar el segundo, ya está aplicado el primero
        when(registroRepo.findFirstByIdOperacionInAndEstadoInAndIdNotOrderByIdAsc(anyCollection(), eq(List.of(EstadoRegistroBot.REGISTRADO)), anyLong()))
                .thenReturn(Optional.empty(), Optional.of(primero));

        assertThat(service.confirmarEfectivo(40L).estado()).isEqualTo(EstadoRegistroBot.REGISTRADO);
        assertThatThrownBy(() -> service.confirmarEfectivo(41L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("repetido");

        assertThat(segundo.getEstado()).isEqualTo(EstadoRegistroBot.PENDIENTE);
        verify(pagoRepo, times(1)).save(any());   // se pagó una sola vez
    }

    // ── Efectivo ─────────────────────────────────────────────────────

    @Test
    void unEfectivoQueSePareceAOtroRecienCargadoLoAvisaEnElBorrador() {
        when(registroRepo.findByMontoAndEstadoInAndFechaHoraAfterAndIdNot(eq(MONTO), anyCollection(), any(LocalDateTime.class), anyLong()))
                .thenReturn(List.of(registro(30L, EstadoRegistroBot.PENDIENTE, FuentePago.EFECTIVO, "Carlos López", LocalDateTime.now().minusMinutes(20))));

        PagoEfectivoRequest req = new PagoEfectivoRequest();
        req.setReceptorNombre("Carlos López");
        req.setMonto(MONTO);
        RegistroPagoBotResponse r = service.registrarPagoEfectivo(req);

        assertThat(r.estado()).isEqualTo(EstadoRegistroBot.PENDIENTE);   // sigue siendo un borrador que una persona confirma
        assertThat(r.mensaje()).contains("Ojo: se parece al registro #30").contains("hace 20 min");
    }

    @Test
    void unEfectivoSinParecidoNoLlevaAviso() {
        PagoEfectivoRequest req = new PagoEfectivoRequest();
        req.setReceptorNombre("Carlos López");
        req.setMonto(MONTO);

        RegistroPagoBotResponse r = service.registrarPagoEfectivo(req);

        assertThat(r.mensaje()).doesNotContain("Ojo");
    }
}
