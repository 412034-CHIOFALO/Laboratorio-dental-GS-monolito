package com.gs.monolito.finanzas.service;

import com.gs.monolito.finanzas.model.ConfiguracionSueldo;
import com.gs.monolito.finanzas.model.FrecuenciaPago;
import com.gs.monolito.finanzas.model.TipoDia;
import com.gs.monolito.finanzas.service.CalculoDevengo.Acreditacion;
import com.gs.monolito.finanzas.service.CalculoDevengo.Credito;
import com.gs.monolito.finanzas.service.CalculoDevengo.Devengo;
import com.gs.monolito.finanzas.service.CalculoDevengo.Periodo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las reglas del devengo por mes calendario. Lo central: un ciclo suma EXACTAMENTE su monto (sin los 5 días de
 * sueldo de más por año que daba el criterio de 30 días fijos), y las ausencias, vacaciones, altas y bajas
 * mueven el total por días reales.
 */
class CalculoDevengoTest {

    private static final LocalDate ALTA = LocalDate.of(2020, 1, 1);

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static ConfiguracionSueldo empleado(FrecuenciaPago f, String monto) {
        return ConfiguracionSueldo.builder().empleadoId(1L).empleadoNombre("Ana").frecuencia(f)
                .montoBase(bd(monto)).fechaAlta(ALTA).build();
    }

    /**
     * Devenga día por día como lo hace el sistema (arrastrando la fracción de centavo) y devuelve lo
     * acreditado en total, en centavos.
     */
    private static BigDecimal acumularDiario(ConfiguracionSueldo c, LocalDate desde, LocalDate hasta, Map<LocalDate, TipoDia> marcas) {
        BigDecimal acreditado = BigDecimal.ZERO;
        BigDecimal resto = c.getRestoDevengo();
        for (LocalDate d = desde; !d.isAfter(hasta); d = d.plusDays(1)) {
            Devengo dev = CalculoDevengo.devengo(c, d, d, marcas);
            Credito cr = CalculoDevengo.redondear(dev.exacto(), resto);
            resto = cr.resto();
            acreditado = acreditado.add(cr.monto());
        }
        c.setRestoDevengo(resto);
        return acreditado;
    }

    // ── Períodos ─────────────────────────────────────────────────────

    @Test
    void elPeriodoDeCadaFrecuenciaEsElCicloReal() {
        assertThat(CalculoDevengo.periodoDe(FrecuenciaPago.MENSUAL, LocalDate.of(2026, 2, 10)))
                .isEqualTo(new Periodo(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)));
        assertThat(CalculoDevengo.periodoDe(FrecuenciaPago.MENSUAL, LocalDate.of(2028, 2, 10)).dias()).isEqualTo(29);
        assertThat(CalculoDevengo.periodoDe(FrecuenciaPago.QUINCENAL, LocalDate.of(2026, 10, 15)))
                .isEqualTo(new Periodo(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15)));
        assertThat(CalculoDevengo.periodoDe(FrecuenciaPago.QUINCENAL, LocalDate.of(2026, 10, 16)))
                .isEqualTo(new Periodo(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 31)));
        assertThat(CalculoDevengo.periodoDe(FrecuenciaPago.QUINCENAL, LocalDate.of(2026, 2, 20)).dias()).isEqualTo(13);
        // 21/10/2026 es miércoles: la semana va del lunes 19 al domingo 25
        assertThat(CalculoDevengo.periodoDe(FrecuenciaPago.SEMANAL, LocalDate.of(2026, 10, 21)))
                .isEqualTo(new Periodo(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25)));
        assertThat(CalculoDevengo.periodoDe(FrecuenciaPago.DIARIO, LocalDate.of(2026, 10, 21)).dias()).isEqualTo(1);
    }

    // ── Un ciclo suma exactamente su monto ───────────────────────────

    @Test
    void unMensualCobraExactamenteSuMontoEnCualquierMesDelAnio() {
        for (int anio : new int[]{2026, 2027, 2028}) {   // 2028 es bisiesto
            for (int mes = 1; mes <= 12; mes++) {
                ConfiguracionSueldo c = empleado(FrecuenciaPago.MENSUAL, "1000000.00");
                LocalDate primero = LocalDate.of(anio, mes, 1);
                BigDecimal total = acumularDiario(c, primero, primero.withDayOfMonth(primero.lengthOfMonth()), Map.of());
                assertThat(total).as("%d-%02d", anio, mes).isEqualByComparingTo("1000000.00");
            }
        }
    }

    @Test
    void unAnioCompletoSumaDoceSueldosYNoDoceConCincoDiasDeMas() {
        ConfiguracionSueldo c = empleado(FrecuenciaPago.MENSUAL, "1000000.00");
        BigDecimal total = acumularDiario(c, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), Map.of());
        assertThat(total).isEqualByComparingTo("12000000.00");   // el criterio de 30 días fijos daba 12.166.665,45
    }

    @Test
    void montosQueNoSonDivisiblesTampocoPierdenCentavos() {
        ConfiguracionSueldo c = empleado(FrecuenciaPago.MENSUAL, "123456.78");
        BigDecimal total = acumularDiario(c, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), Map.of());
        assertThat(total).isEqualByComparingTo("1481481.36");   // 12 × 123.456,78
    }

    @Test
    void unQuincenalCobraSuMontoPorCadaMitadDelMes() {
        ConfiguracionSueldo c = empleado(FrecuenciaPago.QUINCENAL, "350000.00");
        assertThat(acumularDiario(c, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15), Map.of())).isEqualByComparingTo("350000.00");
        assertThat(acumularDiario(c, LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 31), Map.of())).isEqualByComparingTo("350000.00");   // 16 días
        assertThat(acumularDiario(c, LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 28), Map.of())).isEqualByComparingTo("350000.00");     // 13 días
    }

    @Test
    void unSemanalCobraSuMontoPorSemanaAunqueCruceDeMes() {
        ConfiguracionSueldo c = empleado(FrecuenciaPago.SEMANAL, "100000.00");
        // lunes 26/10 al domingo 1/11
        assertThat(acumularDiario(c, LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1), Map.of())).isEqualByComparingTo("100000.00");
    }

    // ── Ausencias, vacaciones, feriados ──────────────────────────────

    @Test
    void unaAusenciaDescuentaUnDiaRealDelMes() {
        ConfiguracionSueldo c = empleado(FrecuenciaPago.MENSUAL, "310000.00");   // octubre tiene 31 días: $10.000 por día
        Map<LocalDate, TipoDia> marcas = new HashMap<>();
        marcas.put(LocalDate.of(2026, 10, 5), TipoDia.AUSENTE);
        marcas.put(LocalDate.of(2026, 10, 6), TipoDia.AUSENTE);
        BigDecimal total = acumularDiario(c, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), marcas);
        assertThat(total).isEqualByComparingTo("290000.00");
    }

    @Test
    void vacacionesFeriadosYLicenciaPagaSeCobran() {
        ConfiguracionSueldo c = empleado(FrecuenciaPago.MENSUAL, "310000.00");
        Map<LocalDate, TipoDia> marcas = new HashMap<>();
        marcas.put(LocalDate.of(2026, 10, 12), TipoDia.FERIADO);
        marcas.put(LocalDate.of(2026, 10, 13), TipoDia.VACACIONES);
        marcas.put(LocalDate.of(2026, 10, 14), TipoDia.LICENCIA_PAGA);
        marcas.put(LocalDate.of(2026, 10, 15), TipoDia.TRABAJADO);
        assertThat(acumularDiario(c, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), marcas)).isEqualByComparingTo("310000.00");
    }

    @Test
    void quienCobraPorDiaSoloCobraLosDiasConMarcaConGoce() {
        ConfiguracionSueldo c = empleado(FrecuenciaPago.DIARIO, "25000.00");
        Map<LocalDate, TipoDia> marcas = new HashMap<>();
        marcas.put(LocalDate.of(2026, 10, 5), TipoDia.TRABAJADO);
        marcas.put(LocalDate.of(2026, 10, 6), TipoDia.TRABAJADO);
        marcas.put(LocalDate.of(2026, 10, 7), TipoDia.AUSENTE);
        marcas.put(LocalDate.of(2026, 10, 8), TipoDia.VACACIONES);
        marcas.put(LocalDate.of(2026, 10, 12), TipoDia.FERIADO);
        // el 9 y el 10 no tienen marca: no se cobran
        Devengo dev = CalculoDevengo.devengo(c, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), marcas);
        assertThat(dev.diasPagos()).isEqualTo(4);
        assertThat(dev.exacto()).isEqualByComparingTo("100000");
    }

    @Test
    void sePagaSegunCadaCaso() {
        LocalDate dia = LocalDate.of(2026, 10, 14);
        // por ciclo: se paga salvo ausencia
        assertThat(CalculoDevengo.sePaga(FrecuenciaPago.MENSUAL, null, dia, ALTA, null)).isTrue();
        assertThat(CalculoDevengo.sePaga(FrecuenciaPago.MENSUAL, TipoDia.AUSENTE, dia, ALTA, null)).isFalse();
        assertThat(CalculoDevengo.sePaga(FrecuenciaPago.MENSUAL, TipoDia.VACACIONES, dia, ALTA, null)).isTrue();
        // por día: solo lo marcado con goce
        assertThat(CalculoDevengo.sePaga(FrecuenciaPago.DIARIO, null, dia, ALTA, null)).isFalse();
        assertThat(CalculoDevengo.sePaga(FrecuenciaPago.DIARIO, TipoDia.TRABAJADO, dia, ALTA, null)).isTrue();
        // fuera del empleo, nunca
        assertThat(CalculoDevengo.sePaga(FrecuenciaPago.MENSUAL, TipoDia.TRABAJADO, dia, dia.plusDays(1), null)).isFalse();
        assertThat(CalculoDevengo.sePaga(FrecuenciaPago.MENSUAL, null, dia, ALTA, dia.minusDays(1))).isFalse();
        assertThat(CalculoDevengo.sePaga(FrecuenciaPago.MENSUAL, null, dia, dia, dia)).isTrue();
    }

    // ── Alta y baja a mitad de mes ───────────────────────────────────

    @Test
    void unAltaAMitadDeMesCobraSoloLosDiasQueLeToquen() {
        ConfiguracionSueldo c = empleado(FrecuenciaPago.MENSUAL, "310000.00");
        c.setFechaAlta(LocalDate.of(2026, 10, 16));   // del 16 al 31: 16 días de 31
        BigDecimal total = acumularDiario(c, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), Map.of());
        assertThat(total).isEqualByComparingTo("160000.00");
    }

    @Test
    void unaBajaAMitadDeMesCobraHastaSuUltimoDia() {
        ConfiguracionSueldo c = empleado(FrecuenciaPago.MENSUAL, "310000.00");
        c.setFechaBaja(LocalDate.of(2026, 10, 10));   // 10 días de 31
        BigDecimal total = acumularDiario(c, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), Map.of());
        assertThat(total).isEqualByComparingTo("100000.00");
    }

    // ── Redondeo ─────────────────────────────────────────────────────

    @Test
    void elRedondeoArrastraLaFraccionEnLugarDePerderla() {
        // 0,004 por día: ningún día llega al centavo, pero 5 días juntos sí deben acreditar 0,02
        BigDecimal resto = BigDecimal.ZERO;
        BigDecimal acreditado = BigDecimal.ZERO;
        for (int i = 0; i < 5; i++) {
            Credito c = CalculoDevengo.redondear(bd("0.004"), resto);
            resto = c.resto();
            acreditado = acreditado.add(c.monto());
        }
        assertThat(acreditado).isEqualByComparingTo("0.02");
    }

    // ── Saldo y adelanto ─────────────────────────────────────────────

    @Test
    void loQueSeDevengaPrimeroCubreElAdelantoPendiente() {
        ConfiguracionSueldo c = empleado(FrecuenciaPago.MENSUAL, "100.00");
        c.setSaldoSobrante(bd("30.00"));
        Acreditacion a = CalculoDevengo.acreditar(c, bd("100.00"));
        assertThat(a.adelantoDescontado()).isEqualByComparingTo("30.00");
        assertThat(c.getSaldoSobrante()).isEqualByComparingTo("0");
        assertThat(c.getSaldoDevengado()).isEqualByComparingTo("70.00");
    }

    @Test
    void descontarMasDeLoQueHayDejaLaDiferenciaComoAdelanto() {
        ConfiguracionSueldo c = empleado(FrecuenciaPago.MENSUAL, "100.00");
        c.setSaldoDevengado(bd("40.00"));   // ya se le pagó casi todo y ahora se descuenta un día de 100
        Acreditacion a = CalculoDevengo.acreditar(c, bd("-100.00"));
        assertThat(a.adelantoGenerado()).isEqualByComparingTo("60.00");
        assertThat(c.getSaldoDevengado()).isEqualByComparingTo("0");
        assertThat(c.getSaldoSobrante()).isEqualByComparingTo("60.00");
    }
}
