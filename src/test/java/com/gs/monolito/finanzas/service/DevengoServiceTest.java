package com.gs.monolito.finanzas.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.finanzas.dto.EmpleadoSueldoResponse;
import com.gs.monolito.finanzas.exception.BusinessException;
import com.gs.monolito.finanzas.model.ConfiguracionSueldo;
import com.gs.monolito.finanzas.model.FrecuenciaPago;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El cierre del devengo: cuándo corre, qué cierra y que un empleado con problemas no frene a los demás.
 * El caso estrella es el que se midió en producción: servidor apagado 11 días → al arrancar se pone al día.
 */
class DevengoServiceTest {

    private static final ZoneId ZONA = ZoneId.of("America/Argentina/Buenos_Aires");

    private ConfiguracionSueldoRepository configRepo;
    private AsistenciaEmpleadoRepository asistenciaRepo;
    private LibroSueldos libro;
    private AuditoriaService auditoria;
    private DevengoService servicio;

    private static Clock reloj(String fecha) {
        return Clock.fixed(Instant.parse(fecha + "T15:00:00Z"), ZONA);
    }

    @BeforeEach
    void preparar() {
        configRepo = mock(ConfiguracionSueldoRepository.class);
        asistenciaRepo = mock(AsistenciaEmpleadoRepository.class);
        libro = mock(LibroSueldos.class);
        auditoria = mock(AuditoriaService.class);
        when(asistenciaRepo.findByEmpleadoIdAndFechaBetween(anyLong(), any(), any())).thenReturn(List.of());
        servicio = armar("2026-10-21");
    }

    private DevengoService armar(String hoy) {
        return new DevengoService(configRepo, asistenciaRepo, libro, auditoria, reloj(hoy), mock(PlatformTransactionManager.class));
    }

    private ConfiguracionSueldo mensual(long id, String nombre, String monto, String alta, String ultimoDevengo) {
        ConfiguracionSueldo c = ConfiguracionSueldo.builder().empleadoId(id).empleadoNombre(nombre)
                .frecuencia(FrecuenciaPago.MENSUAL).montoBase(new BigDecimal(monto))
                .fechaAlta(LocalDate.parse(alta)).build();
        if (ultimoDevengo != null) c.setUltimoDevengoCalculado(LocalDate.parse(ultimoDevengo));
        when(configRepo.findByEmpleadoId(id)).thenReturn(Optional.of(c));
        when(configRepo.save(c)).thenReturn(c);
        return c;
    }

    // ── Servidor apagado ─────────────────────────────────────────────

    @Test
    void alArrancarDespuesDeEstarApagadoSePoneAlDiaDeUnaVez() {
        // Producción real: último cálculo el 10/10 y hoy es 21/10 → 11 días sin devengar.
        ConfiguracionSueldo c = mensual(1L, "Marcos", "1000000.00", "2026-10-01", "2026-10-10");
        when(configRepo.findByActivoTrueOrderByEmpleadoNombreAsc()).thenReturn(List.of(c));

        int actualizados = servicio.devengarTodos();

        assertThat(actualizados).isEqualTo(1);
        // días 10 al 20 de octubre: 11 días de 31 → 11/31 de un millón
        assertThat(c.getSaldoDevengado()).isEqualByComparingTo("354838.71");
        assertThat(c.getUltimoDevengoCalculado()).isEqualTo(LocalDate.of(2026, 10, 21));
        verify(libro).registrar(eq(c), eq(TipoMovimientoSueldo.DEVENGO), eq(new BigDecimal("354838.71")),
                eq(LocalDate.of(2026, 10, 20)), any());
    }

    @Test
    void repetirElCierreNoDevengaDeNuevo() {
        ConfiguracionSueldo c = mensual(1L, "Marcos", "1000000.00", "2026-10-01", "2026-10-10");
        when(configRepo.findByActivoTrueOrderByEmpleadoNombreAsc()).thenReturn(List.of(c));

        servicio.devengarTodos();
        BigDecimal saldo = c.getSaldoDevengado();
        int segundaVez = servicio.devengarTodos();

        assertThat(segundaVez).isZero();
        assertThat(c.getSaldoDevengado()).isEqualByComparingTo(saldo);
        verify(libro, times(1)).registrar(any(), any(), any(), any(), any());
    }

    @Test
    void unEmpleadoNuevoDevengaDesdeSuAlta() {
        ConfiguracionSueldo c = mensual(1L, "Nueva", "310000.00", "2026-10-15", null);
        when(configRepo.findByActivoTrueOrderByEmpleadoNombreAsc()).thenReturn(List.of(c));

        servicio.devengarTodos();

        // del 15 al 20 de octubre: 6 días de $10.000
        assertThat(c.getSaldoDevengado()).isEqualByComparingTo("60000.00");
    }

    @Test
    void elDiaDeHoyTodaviaNoSeCierra() {
        ConfiguracionSueldo c = mensual(1L, "Ana", "310000.00", "2026-10-21", null);
        when(configRepo.findByActivoTrueOrderByEmpleadoNombreAsc()).thenReturn(List.of(c));

        assertThat(servicio.devengarTodos()).isZero();
        assertThat(c.getSaldoDevengado()).isEqualByComparingTo("0");
    }

    @Test
    void siUnEmpleadoFallaLosDemasIgualSeActualizan() {
        ConfiguracionSueldo roto = mensual(1L, "Roto", "310000.00", "2026-10-01", "2026-10-10");
        ConfiguracionSueldo bueno = mensual(2L, "Bueno", "310000.00", "2026-10-01", "2026-10-10");
        when(configRepo.findByActivoTrueOrderByEmpleadoNombreAsc()).thenReturn(List.of(roto, bueno));
        when(configRepo.findByEmpleadoId(1L)).thenThrow(new RuntimeException("conflicto de versión"));

        int actualizados = servicio.devengarTodos();

        assertThat(actualizados).isEqualTo(1);
        assertThat(bueno.getSaldoDevengado()).isEqualByComparingTo("110000.00");   // 11 días × $10.000
        assertThat(roto.getSaldoDevengado()).isEqualByComparingTo("0");
    }

    @Test
    void unEmpleadoDadoDeBajaNoDevenga() {
        ConfiguracionSueldo c = mensual(1L, "Baja", "310000.00", "2026-01-01", "2026-10-10");
        c.setActivo(false);

        assertThat(servicio.devengarEmpleado(1L, LocalDate.of(2026, 10, 21))).isFalse();
        assertThat(c.getSaldoDevengado()).isEqualByComparingTo("0");
    }

    @Test
    void conUnAdelantoPendienteElDevengoPrimeroLoCubre() {
        ConfiguracionSueldo c = mensual(1L, "Adelanto", "310000.00", "2026-10-01", "2026-10-10");
        c.setSaldoSobrante(new BigDecimal("50000.00"));
        when(configRepo.findByActivoTrueOrderByEmpleadoNombreAsc()).thenReturn(List.of(c));

        servicio.devengarTodos();   // devenga $110.000

        assertThat(c.getSaldoSobrante()).isEqualByComparingTo("0");
        assertThat(c.getSaldoDevengado()).isEqualByComparingTo("60000.00");
    }

    // ── Baja ─────────────────────────────────────────────────────────

    @Test
    void laBajaDevengaHastaSuUltimoDiaYDejaDeDevengar() {
        ConfiguracionSueldo c = mensual(1L, "Marcos", "1000000.00", "2026-10-01", "2026-10-10");

        EmpleadoSueldoResponse r = servicio.darDeBaja(1L, LocalDate.of(2026, 10, 15), "renuncia");

        // 10 al 15 de octubre: 6 días de 31
        assertThat(c.getSaldoDevengado()).isEqualByComparingTo("193548.39");
        assertThat(c.isActivo()).isFalse();
        assertThat(c.getFechaBaja()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(c.getUltimoDevengoCalculado()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(r.activo()).isFalse();
        verify(auditoria).registrar(any(), eq("SUELDO"), eq("Empleado dado de baja"), any(), any());
    }

    @Test
    void unaBajaAnteriorAlUltimoCierreDescuentaLoDevengadoDeMas() {
        // Ya se devengó hasta el 20/10 (20 días = $645.161,29). Se avisa que dejó de venir el 15/10.
        ConfiguracionSueldo c = mensual(1L, "Marcos", "1000000.00", "2026-10-01", "2026-10-21");
        c.setSaldoDevengado(new BigDecimal("645161.29"));

        servicio.darDeBaja(1L, LocalDate.of(2026, 10, 15), null);

        // quedan 15 días de 31 = $483.870,97 (se descuentan los 5 días del 16 al 20)
        assertThat(c.getSaldoDevengado()).isBetween(new BigDecimal("483870.96"), new BigDecimal("483870.98"));
        verify(libro).registrar(eq(c), eq(TipoMovimientoSueldo.AJUSTE_ASISTENCIA), any(), any(), any());
    }

    @Test
    void noSePuedeDarDeBajaConFechaFuturaNiAnteriorAlAltaNiDosVeces() {
        mensual(1L, "Marcos", "1000000.00", "2026-10-01", "2026-10-10");

        assertThatThrownBy(() -> servicio.darDeBaja(1L, LocalDate.of(2026, 10, 30), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("futura");
        assertThatThrownBy(() -> servicio.darDeBaja(1L, LocalDate.of(2026, 9, 30), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("anterior al alta");

        servicio.darDeBaja(1L, LocalDate.of(2026, 10, 12), null);
        assertThatThrownBy(() -> servicio.darDeBaja(1L, LocalDate.of(2026, 10, 13), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ya está dado de baja");
    }

    @Test
    void alReactivarElTiempoDeBajaNoSeDevenga() {
        ConfiguracionSueldo c = mensual(1L, "Marcos", "310000.00", "2026-01-01", "2026-10-01");
        servicio.darDeBaja(1L, LocalDate.of(2026, 9, 30), null);
        c.setSaldoDevengado(BigDecimal.ZERO);

        servicio.reactivar(1L, LocalDate.of(2026, 10, 19));
        servicio.devengarEmpleado(1L, LocalDate.of(2026, 10, 21));

        // solo el 19 y el 20: 2 días de $10.000, no todo octubre
        assertThat(c.isActivo()).isTrue();
        assertThat(c.getFechaBaja()).isNull();
        assertThat(c.getFechaAlta()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(c.getSaldoDevengado()).isEqualByComparingTo("20000.00");
    }

    @Test
    void noSePuedeReactivarAAlguienActivoNiAntesDeLaBaja() {
        ConfiguracionSueldo c = mensual(1L, "Marcos", "310000.00", "2026-01-01", "2026-10-10");
        assertThatThrownBy(() -> servicio.reactivar(1L, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ya está activo");

        servicio.darDeBaja(1L, LocalDate.of(2026, 10, 12), null);
        assertThatThrownBy(() -> servicio.reactivar(1L, LocalDate.of(2026, 10, 12)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("posterior a la baja");
        verify(configRepo, never()).delete(any(ConfiguracionSueldo.class));
        assertThat(c.getFechaBaja()).isEqualTo(LocalDate.of(2026, 10, 12));
    }
}
