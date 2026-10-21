package com.gs.monolito.finanzas.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.finanzas.dto.AsistenciaMesResponse;
import com.gs.monolito.finanzas.dto.MarcarAsistenciaResponse;
import com.gs.monolito.finanzas.exception.BusinessException;
import com.gs.monolito.finanzas.model.AsistenciaEmpleado;
import com.gs.monolito.finanzas.model.ConfiguracionSueldo;
import com.gs.monolito.finanzas.model.FrecuenciaPago;
import com.gs.monolito.finanzas.model.TipoDia;
import com.gs.monolito.finanzas.model.TipoMovimientoSueldo;
import com.gs.monolito.finanzas.repository.AsistenciaEmpleadoRepository;
import com.gs.monolito.finanzas.repository.ConfiguracionSueldoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El calendario: cargar o corregir un día. Un día ya devengado corrige el saldo en el acto; hoy y los futuros no
 * (el próximo cierre los cuenta). Hoy es 21/10/2026: ya está devengado hasta el 20.
 */
class AsistenciaServiceTest {

    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-21T15:00:00Z"), ZoneId.of("America/Argentina/Buenos_Aires"));

    private ConfiguracionSueldoRepository configRepo;
    private AsistenciaEmpleadoRepository asistenciaRepo;
    private LibroSueldos libro;
    private AsistenciaService servicio;

    @BeforeEach
    void preparar() {
        configRepo = mock(ConfiguracionSueldoRepository.class);
        asistenciaRepo = mock(AsistenciaEmpleadoRepository.class);
        libro = mock(LibroSueldos.class);
        AuditoriaService auditoria = mock(AuditoriaService.class);
        when(asistenciaRepo.findByEmpleadoIdAndFechaBetween(anyLong(), any(), any())).thenReturn(List.of());
        DevengoService devengo = new DevengoService(configRepo, asistenciaRepo, libro, auditoria, RELOJ, mock(PlatformTransactionManager.class));
        servicio = new AsistenciaService(configRepo, asistenciaRepo, devengo, libro, auditoria, RELOJ);
    }

    /** Empleado con el devengo cerrado hasta el 20/10 y $100.000 de saldo. */
    private ConfiguracionSueldo empleado(FrecuenciaPago f, String monto) {
        ConfiguracionSueldo c = ConfiguracionSueldo.builder().empleadoId(1L).empleadoNombre("Ana")
                .frecuencia(f).montoBase(new BigDecimal(monto)).fechaAlta(LocalDate.of(2026, 1, 1)).build();
        c.setUltimoDevengoCalculado(LocalDate.of(2026, 10, 21));
        c.setSaldoDevengado(new BigDecimal("100000.00"));
        when(configRepo.findByEmpleadoId(1L)).thenReturn(Optional.of(c));
        when(configRepo.save(c)).thenReturn(c);
        return c;
    }

    private static AsistenciaEmpleado marca(LocalDate fecha, TipoDia tipo) {
        return AsistenciaEmpleado.builder().empleadoId(1L).fecha(fecha).tipo(tipo).build();
    }

    // ── Días ya devengados: corrigen el saldo ────────────────────────

    @Test
    void marcarUnaAusenciaDeUnDiaYaDevengadoDescuentaLoQueValia() {
        ConfiguracionSueldo c = empleado(FrecuenciaPago.MENSUAL, "310000.00");   // octubre: $10.000 por día

        MarcarAsistenciaResponse r = servicio.marcar(1L, List.of(LocalDate.of(2026, 10, 5)), TipoDia.AUSENTE, "faltó");

        assertThat(r.diasModificados()).isEqualTo(1);
        assertThat(r.ajuste()).isEqualByComparingTo("-10000.00");
        assertThat(c.getSaldoDevengado()).isEqualByComparingTo("90000.00");
        verify(asistenciaRepo).save(any(AsistenciaEmpleado.class));
        verify(libro).registrar(eq(c), eq(TipoMovimientoSueldo.AJUSTE_ASISTENCIA), eq(new BigDecimal("-10000.00")), any(), any());
    }

    @Test
    void borrarLaMarcaDeUnaAusenciaDevuelveElDia() {
        ConfiguracionSueldo c = empleado(FrecuenciaPago.MENSUAL, "310000.00");
        LocalDate dia = LocalDate.of(2026, 10, 5);
        when(asistenciaRepo.findByEmpleadoIdAndFechaBetween(1L, dia, dia)).thenReturn(List.of(marca(dia, TipoDia.AUSENTE)));

        MarcarAsistenciaResponse r = servicio.marcar(1L, List.of(dia), null, null);

        assertThat(r.ajuste()).isEqualByComparingTo("10000.00");
        assertThat(c.getSaldoDevengado()).isEqualByComparingTo("110000.00");
        verify(asistenciaRepo).delete(any(AsistenciaEmpleado.class));
    }

    @Test
    void convertirUnaAusenciaEnVacacionesDevuelveElDia() {
        ConfiguracionSueldo c = empleado(FrecuenciaPago.MENSUAL, "310000.00");
        LocalDate dia = LocalDate.of(2026, 10, 5);
        when(asistenciaRepo.findByEmpleadoIdAndFechaBetween(1L, dia, dia)).thenReturn(List.of(marca(dia, TipoDia.AUSENTE)));

        servicio.marcar(1L, List.of(dia), TipoDia.VACACIONES, null);

        assertThat(c.getSaldoDevengado()).isEqualByComparingTo("110000.00");
    }

    @Test
    void variosDiasSeDescuentanJuntosYSePuedenElegirSueltos() {
        ConfiguracionSueldo c = empleado(FrecuenciaPago.MENSUAL, "310000.00");

        MarcarAsistenciaResponse r = servicio.marcar(1L,
                List.of(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 14)), TipoDia.AUSENTE, null);

        assertThat(r.diasModificados()).isEqualTo(3);
        assertThat(c.getSaldoDevengado()).isEqualByComparingTo("70000.00");
    }

    @Test
    void quienCobraPorDiaGanaElDiaAlMarcarloTrabajado() {
        ConfiguracionSueldo c = empleado(FrecuenciaPago.DIARIO, "25000.00");

        servicio.marcar(1L, List.of(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6)), TipoDia.TRABAJADO, null);

        assertThat(c.getSaldoDevengado()).isEqualByComparingTo("150000.00");   // 100.000 + 2 × 25.000
    }

    @Test
    void marcarAusenteAQuienCobraPorDiaYNoTeniaMarcaNoCambiaNada() {
        ConfiguracionSueldo c = empleado(FrecuenciaPago.DIARIO, "25000.00");

        MarcarAsistenciaResponse r = servicio.marcar(1L, List.of(LocalDate.of(2026, 10, 5)), TipoDia.AUSENTE, null);

        assertThat(r.ajuste()).isEqualByComparingTo("0");
        assertThat(c.getSaldoDevengado()).isEqualByComparingTo("100000.00");
        verify(libro, never()).registrar(any(), any(), any(), any(), any());
    }

    // ── Hoy y el futuro: no tocan el saldo ───────────────────────────

    @Test
    void hoyYLosDiasFuturosSeGuardanSinMoverElSaldo() {
        ConfiguracionSueldo c = empleado(FrecuenciaPago.MENSUAL, "310000.00");

        MarcarAsistenciaResponse r = servicio.marcar(1L,
                List.of(LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 22), LocalDate.of(2026, 11, 2)), TipoDia.VACACIONES, null);

        assertThat(r.diasModificados()).isEqualTo(3);
        assertThat(r.ajuste()).isEqualByComparingTo("0");
        assertThat(c.getSaldoDevengado()).isEqualByComparingTo("100000.00");
        verify(libro, never()).registrar(any(), any(), any(), any(), any());
    }

    @Test
    void marcarDeNuevoLoMismoNoHaceNada() {
        empleado(FrecuenciaPago.MENSUAL, "310000.00");
        LocalDate dia = LocalDate.of(2026, 10, 5);
        when(asistenciaRepo.findByEmpleadoIdAndFechaBetween(1L, dia, dia)).thenReturn(List.of(marca(dia, TipoDia.AUSENTE)));

        MarcarAsistenciaResponse r = servicio.marcar(1L, List.of(dia), TipoDia.AUSENTE, null);

        assertThat(r.diasModificados()).isZero();
        verify(asistenciaRepo, never()).save(any());
        verify(libro, never()).registrar(any(), any(), any(), any(), any());
    }

    // ── Validaciones y avisos ────────────────────────────────────────

    @Test
    void noSePuedeMarcarUnDiaAnteriorAlAlta() {
        empleado(FrecuenciaPago.MENSUAL, "310000.00");

        assertThatThrownBy(() -> servicio.marcar(1L, List.of(LocalDate.of(2025, 12, 31)), TipoDia.AUSENTE, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("anterior al alta");
    }

    @Test
    void noSePuedeMarcarUnDiaPosteriorALaBaja() {
        ConfiguracionSueldo c = empleado(FrecuenciaPago.MENSUAL, "310000.00");
        c.setFechaBaja(LocalDate.of(2026, 10, 10));

        assertThatThrownBy(() -> servicio.marcar(1L, List.of(LocalDate.of(2026, 10, 11)), TipoDia.AUSENTE, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("posterior a la baja");
    }

    @Test
    void noSePuedenCargarDiasConMasDeUnAnioDeAnticipacion() {
        empleado(FrecuenciaPago.MENSUAL, "310000.00");

        assertThatThrownBy(() -> servicio.marcar(1L, List.of(LocalDate.of(2028, 1, 1)), TipoDia.VACACIONES, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("un año");
    }

    @Test
    void avisaCuandoLasVacacionesSuperanElCupoDelAnio() {
        empleado(FrecuenciaPago.MENSUAL, "310000.00");   // cupo: 14 días
        when(asistenciaRepo.countByEmpleadoIdAndTipoAndFechaBetween(eq(1L), eq(TipoDia.VACACIONES), any(), any())).thenReturn(16L);

        MarcarAsistenciaResponse r = servicio.marcar(1L, List.of(LocalDate.of(2026, 12, 1)), TipoDia.VACACIONES, null);

        assertThat(r.advertencias()).singleElement().asString().contains("16 días").contains("14");
    }

    // ── Vista del mes ────────────────────────────────────────────────

    @Test
    void elResumenDelMesCuentaDiasPagosAusenciasYSueldo() {
        empleado(FrecuenciaPago.MENSUAL, "310000.00");
        when(asistenciaRepo.findByEmpleadoIdAndFechaBetween(eq(1L), eq(LocalDate.of(2026, 10, 1)), eq(LocalDate.of(2026, 10, 31))))
                .thenReturn(List.of(
                        marca(LocalDate.of(2026, 10, 5), TipoDia.AUSENTE),
                        marca(LocalDate.of(2026, 10, 6), TipoDia.AUSENTE),
                        marca(LocalDate.of(2026, 10, 12), TipoDia.FERIADO),
                        marca(LocalDate.of(2026, 10, 13), TipoDia.VACACIONES)));
        when(asistenciaRepo.countByEmpleadoIdAndTipoAndFechaBetween(eq(1L), eq(TipoDia.VACACIONES), any(), any())).thenReturn(3L);

        AsistenciaMesResponse mes = servicio.mes(1L, YearMonth.of(2026, 10));

        assertThat(mes.dias()).hasSize(31);
        assertThat(mes.resumen().diasPagos()).isEqualTo(29);
        assertThat(mes.resumen().diasNoPagos()).isEqualTo(2);
        assertThat(mes.resumen().ausencias()).isEqualTo(2);
        assertThat(mes.resumen().feriados()).isEqualTo(1);
        assertThat(mes.resumen().vacaciones()).isEqualTo(1);
        assertThat(mes.resumen().sueldoDelMes()).isEqualByComparingTo("290000.00");
        assertThat(mes.resumen().vacacionesTomadasEnElAnio()).isEqualTo(3);
        assertThat(mes.resumen().vacacionesDisponibles()).isEqualTo(11);
        assertThat(mes.dias().get(4).tipo()).isEqualTo(TipoDia.AUSENTE);
        assertThat(mes.dias().get(4).sePaga()).isFalse();
    }

    @Test
    void enElMesDelAltaLosDiasPreviosNoCuentan() {
        ConfiguracionSueldo c = empleado(FrecuenciaPago.MENSUAL, "310000.00");
        c.setFechaAlta(LocalDate.of(2026, 10, 16));

        AsistenciaMesResponse mes = servicio.mes(1L, YearMonth.of(2026, 10));

        assertThat(mes.resumen().diasPagos()).isEqualTo(16);
        assertThat(mes.resumen().sueldoDelMes()).isEqualByComparingTo("160000.00");
        assertThat(mes.dias().get(0).enRelacion()).isFalse();
        assertThat(mes.dias().get(15).enRelacion()).isTrue();
    }

    @Test
    void paraQuienCobraPorDiaSoloCuentanLosDiasMarcados() {
        empleado(FrecuenciaPago.DIARIO, "25000.00");
        when(asistenciaRepo.findByEmpleadoIdAndFechaBetween(eq(1L), eq(LocalDate.of(2026, 10, 1)), eq(LocalDate.of(2026, 10, 31))))
                .thenReturn(List.of(
                        marca(LocalDate.of(2026, 10, 5), TipoDia.TRABAJADO),
                        marca(LocalDate.of(2026, 10, 6), TipoDia.TRABAJADO),
                        marca(LocalDate.of(2026, 10, 7), TipoDia.AUSENTE)));

        AsistenciaMesResponse mes = servicio.mes(1L, YearMonth.of(2026, 10));

        assertThat(mes.resumen().diasPagos()).isEqualTo(2);
        assertThat(mes.resumen().sueldoDelMes()).isEqualByComparingTo("50000.00");
    }
}
