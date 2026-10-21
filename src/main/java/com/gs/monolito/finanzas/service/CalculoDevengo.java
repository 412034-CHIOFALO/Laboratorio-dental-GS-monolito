package com.gs.monolito.finanzas.service;

import com.gs.monolito.finanzas.model.ConfiguracionSueldo;
import com.gs.monolito.finanzas.model.FrecuenciaPago;
import com.gs.monolito.finanzas.model.TipoDia;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;

/**
 * Las reglas del devengo de sueldos, sin base de datos ni reloj: todo entra por parámetro, así que se puede
 * probar mes por mes, año bisiesto incluido.
 *
 * <p><b>Criterio: mes calendario.</b> El sueldo de un ciclo se reparte por los días REALES de ese ciclo:
 * un mensual cobra exactamente su monto por mes (en febrero cada día vale más que en enero, pero el mes suma
 * lo mismo), un quincenal cobra por mitades del mes (1 al 15 y 16 al último) y un semanal por semana de lunes
 * a domingo. Quien cobra por día ({@link FrecuenciaPago#DIARIO}) cobra su monto por cada día con goce.</p>
 */
public final class CalculoDevengo {

    /** Decimales con los que se opera por dentro; al saldo se llega recién después de acumular el resto. */
    static final int ESCALA_INTERNA = 10;

    private CalculoDevengo() {}

    /** Un ciclo de pago: desde {@link #inicio} hasta {@link #fin}, ambos inclusive. */
    public record Periodo(LocalDate inicio, LocalDate fin) {
        public int dias() {
            return (int) ChronoUnit.DAYS.between(inicio, fin) + 1;
        }
    }

    /** Lo devengado en un rango de días: el importe exacto (sin redondear) y cuántos días se pagaron. */
    public record Devengo(BigDecimal exacto, int diasPagos) {}

    /** Importe en centavos y la fracción que queda pendiente para la próxima vez. */
    public record Credito(BigDecimal monto, BigDecimal resto) {}

    /** Qué pasó al acreditar un importe contra el saldo. */
    public record Acreditacion(BigDecimal adelantoDescontado, BigDecimal adelantoGenerado) {}

    /** El ciclo de pago al que pertenece un día. */
    public static Periodo periodoDe(FrecuenciaPago frecuencia, LocalDate dia) {
        return switch (frecuencia) {
            case DIARIO -> new Periodo(dia, dia);
            case SEMANAL -> {
                LocalDate lunes = dia.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield new Periodo(lunes, lunes.plusDays(6));
            }
            case QUINCENAL -> dia.getDayOfMonth() <= 15
                    ? new Periodo(dia.withDayOfMonth(1), dia.withDayOfMonth(15))
                    : new Periodo(dia.withDayOfMonth(16), dia.with(TemporalAdjusters.lastDayOfMonth()));
            case MENSUAL -> new Periodo(dia.withDayOfMonth(1), dia.with(TemporalAdjusters.lastDayOfMonth()));
        };
    }

    /** Lo que vale un día con goce de sueldo, con todos los decimales (sin redondear a centavos). */
    public static BigDecimal tarifaDelDia(FrecuenciaPago frecuencia, BigDecimal montoBase, LocalDate dia) {
        if (frecuencia == FrecuenciaPago.DIARIO) return montoBase;
        int diasDelCiclo = periodoDe(frecuencia, dia).dias();
        return montoBase.divide(BigDecimal.valueOf(diasDelCiclo), ESCALA_INTERNA, RoundingMode.HALF_UP);
    }

    /**
     * ¿Se paga ese día? Fuera del período de empleo (antes del alta o después de la baja) nunca. Sin marca:
     * se paga a quien cobra por ciclo y NO a quien cobra por día. Con marca: según el {@link TipoDia}.
     */
    public static boolean sePaga(FrecuenciaPago frecuencia, TipoDia marca, LocalDate dia, LocalDate alta, LocalDate baja) {
        if (alta != null && dia.isBefore(alta)) return false;
        if (baja != null && dia.isAfter(baja)) return false;
        if (marca == null) return frecuencia != FrecuenciaPago.DIARIO;
        return marca.isPago();
    }

    /** Devengo de un empleado entre dos días (ambos inclusive), con las marcas de asistencia dadas. */
    public static Devengo devengo(ConfiguracionSueldo c, LocalDate desde, LocalDate hasta, Map<LocalDate, TipoDia> marcas) {
        BigDecimal exacto = BigDecimal.ZERO;
        int dias = 0;
        for (LocalDate d = desde; !d.isAfter(hasta); d = d.plusDays(1)) {
            if (sePaga(c.getFrecuencia(), marcas.get(d), d, c.getFechaAlta(), c.getFechaBaja())) {
                exacto = exacto.add(tarifaDelDia(c.getFrecuencia(), c.getMontoBase(), d));
                dias++;
            }
        }
        return new Devengo(exacto, dias);
    }

    /**
     * Pasa un importe exacto a centavos arrastrando la fracción que sobra o falta: la suma de lo acreditado
     * en el tiempo siempre es el importe real redondeado, sin perder ni inventar centavos.
     */
    public static Credito redondear(BigDecimal exacto, BigDecimal restoPrevio) {
        BigDecimal total = exacto.add(restoPrevio);
        BigDecimal monto = total.setScale(2, RoundingMode.HALF_UP);
        return new Credito(monto, total.subtract(monto).setScale(ESCALA_INTERNA, RoundingMode.HALF_UP));
    }

    /**
     * Aplica un importe (con signo) al saldo del empleado.
     * <ul>
     *   <li>Si es a favor y tiene un adelanto pendiente (se le pagó de más antes), primero se descuenta del adelanto.</li>
     *   <li>Si es en contra y el saldo no alcanza (ya se le pagó lo que ahora se descuenta), lo que falta pasa a adelanto.</li>
     * </ul>
     */
    public static Acreditacion acreditar(ConfiguracionSueldo c, BigDecimal monto) {
        BigDecimal descontado = BigDecimal.ZERO;
        BigDecimal generado = BigDecimal.ZERO;
        if (monto.signum() > 0) {
            BigDecimal adelanto = c.getSaldoSobrante();
            if (adelanto.signum() > 0) {
                descontado = monto.min(adelanto);
                c.setSaldoSobrante(adelanto.subtract(descontado));
                monto = monto.subtract(descontado);
            }
            c.setSaldoDevengado(c.getSaldoDevengado().add(monto));
        } else if (monto.signum() < 0) {
            BigDecimal nuevo = c.getSaldoDevengado().add(monto);
            if (nuevo.signum() < 0) {
                generado = nuevo.negate();
                c.setSaldoSobrante(c.getSaldoSobrante().add(generado));
                nuevo = BigDecimal.ZERO;
            }
            c.setSaldoDevengado(nuevo);
        }
        return new Acreditacion(descontado, generado);
    }
}
