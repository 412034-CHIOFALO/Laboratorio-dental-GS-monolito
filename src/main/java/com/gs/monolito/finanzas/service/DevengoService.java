package com.gs.monolito.finanzas.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.common.security.CurrentUser;
import com.gs.monolito.finanzas.dto.EmpleadoSueldoResponse;
import com.gs.monolito.finanzas.exception.BusinessException;
import com.gs.monolito.finanzas.exception.ResourceNotFoundException;
import com.gs.monolito.finanzas.model.ConfiguracionSueldo;
import com.gs.monolito.finanzas.model.TipoDia;
import com.gs.monolito.finanzas.model.TipoMovimientoSueldo;
import com.gs.monolito.finanzas.repository.AsistenciaEmpleadoRepository;
import com.gs.monolito.finanzas.repository.ConfiguracionSueldoRepository;
import com.gs.monolito.finanzas.service.CalculoDevengo.Acreditacion;
import com.gs.monolito.finanzas.service.CalculoDevengo.Credito;
import com.gs.monolito.finanzas.service.CalculoDevengo.Devengo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Cierra los días y suma al saldo de cada empleado lo que ganó (ver {@link CalculoDevengo} para las reglas).
 *
 * <p><b>Cuándo corre:</b> al arrancar la aplicación y cada hora (ver {@link DevengoScheduler}). Es idempotente:
 * si ya está al día no hace nada. Antes corría solo a las 00:05, y con un servidor que no está prendido a esa
 * hora los saldos quedaban días atrasados. Ahora, apenas arranca, se pone al día de una vez.</p>
 *
 * <p><b>Cada empleado en su propia transacción:</b> si a uno le falla el cálculo (por ejemplo, lo estaban
 * editando al mismo tiempo), los demás igual se actualizan y a ese se lo reintenta en la próxima pasada.</p>
 */
@Service
@Slf4j
public class DevengoService {

    private static final DateTimeFormatter DIA_MES = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter DIA_MES_ANIO = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ConfiguracionSueldoRepository configRepo;
    private final AsistenciaEmpleadoRepository asistenciaRepo;
    private final LibroSueldos libro;
    private final AuditoriaService auditoria;
    private final Clock reloj;
    private final TransactionTemplate tx;

    public DevengoService(ConfiguracionSueldoRepository configRepo,
                          AsistenciaEmpleadoRepository asistenciaRepo,
                          LibroSueldos libro,
                          AuditoriaService auditoria,
                          Clock reloj,
                          PlatformTransactionManager transactionManager) {
        this.configRepo = configRepo;
        this.asistenciaRepo = asistenciaRepo;
        this.libro = libro;
        this.auditoria = auditoria;
        this.reloj = reloj;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Pone al día a todos los empleados activos. Devuelve a cuántos les cerró días. */
    public int devengarTodos() {
        LocalDate hoy = LocalDate.now(reloj);
        int actualizados = 0;
        for (ConfiguracionSueldo c : configRepo.findByActivoTrueOrderByEmpleadoNombreAsc()) {
            try {
                Boolean hizo = tx.execute(status -> devengarEmpleado(c.getEmpleadoId(), hoy));
                if (Boolean.TRUE.equals(hizo)) actualizados++;
            } catch (Exception e) {
                log.warn("[DEVENGO] No se pudo devengar a {}; se reintenta en la próxima pasada: {}",
                        c.getEmpleadoNombre(), e.getMessage());
            }
        }
        return actualizados;
    }

    boolean devengarEmpleado(Long empleadoId, LocalDate hoy) {
        ConfiguracionSueldo c = configRepo.findByEmpleadoId(empleadoId).orElse(null);
        if (c == null || !c.isActivo()) return false;
        return devengarConfig(c, hoy);
    }

    /**
     * Cierra los días pendientes de un empleado ya cargado, hasta ayer inclusive. Lo usa también quien está
     * por cambiarle el sueldo: lo ya trabajado se liquida con el sueldo anterior.
     *
     * @return true si cerró algún día
     */
    public boolean devengarConfig(ConfiguracionSueldo c, LocalDate hoy) {
        LocalDate desde = primerDiaSinCerrar(c);
        if (!desde.isBefore(hoy)) return false;
        LocalDate hasta = hoy.minusDays(1);

        Devengo dev = CalculoDevengo.devengo(c, desde, hasta, marcasEntre(c.getEmpleadoId(), desde, hasta));
        c.setUltimoDevengoCalculado(hoy);
        BigDecimal acreditado = BigDecimal.ZERO;
        if (dev.diasPagos() > 0) {
            acreditado = acreditar(c, dev.exacto(), TipoMovimientoSueldo.DEVENGO, hasta,
                    "Devengo del " + DIA_MES.format(desde) + " al " + DIA_MES.format(hasta)
                            + " (" + dev.diasPagos() + (dev.diasPagos() == 1 ? " día pago)" : " días pagos)"));
        }
        configRepo.save(c);
        if (acreditado.signum() != 0) {
            log.info("[DEVENGO] {} — días cerrados hasta el {}: +${} (se le debe ${})",
                    c.getEmpleadoNombre(), DIA_MES.format(hasta), acreditado, c.getSaldoDevengado());
        }
        return true;
    }

    /**
     * Acredita un importe (con signo) en el saldo: arrastra la fracción de centavo, aplica el adelanto
     * pendiente si corresponde y deja la línea en el libro.
     *
     * @return lo que efectivamente se acreditó, en centavos
     */
    public BigDecimal acreditar(ConfiguracionSueldo c, BigDecimal exacto, TipoMovimientoSueldo tipo, LocalDate fecha, String detalle) {
        Credito credito = CalculoDevengo.redondear(exacto, c.getRestoDevengo());
        c.setRestoDevengo(credito.resto());
        if (credito.monto().signum() == 0) return BigDecimal.ZERO;

        Acreditacion a = CalculoDevengo.acreditar(c, credito.monto());
        StringBuilder texto = new StringBuilder(detalle);
        if (a.adelantoDescontado().signum() > 0) {
            texto.append(" · descontó $").append(a.adelantoDescontado().toPlainString()).append(" del adelanto");
        }
        if (a.adelantoGenerado().signum() > 0) {
            texto.append(" · ya había cobrado ese tramo: queda un adelanto de $").append(a.adelantoGenerado().toPlainString());
        }
        libro.registrar(c, tipo, credito.monto(), fecha, texto.toString());
        return credito.monto();
    }

    /** Primer día cuyo devengo todavía no se cerró. */
    public static LocalDate primerDiaSinCerrar(ConfiguracionSueldo c) {
        LocalDate ultimo = c.getUltimoDevengoCalculado();
        return (ultimo == null || ultimo.isBefore(c.getFechaAlta())) ? c.getFechaAlta() : ultimo;
    }

    public Map<LocalDate, TipoDia> marcasEntre(Long empleadoId, LocalDate desde, LocalDate hasta) {
        Map<LocalDate, TipoDia> marcas = new HashMap<>();
        asistenciaRepo.findByEmpleadoIdAndFechaBetween(empleadoId, desde, hasta)
                .forEach(a -> marcas.put(a.getFecha(), a.getTipo()));
        return marcas;
    }

    // ── Baja y reactivación ──────────────────────────────────────────

    /**
     * Da de baja a un empleado. {@code fechaBaja} es su último día de trabajo. Se le devenga hasta ese día
     * (si faltaba cerrarlo) o se le descuenta lo devengado de más (si la baja es anterior al último cierre),
     * y deja de devengar. Lo que ya se le debe sigue siendo suyo: se le puede pagar igual.
     */
    @Transactional
    public EmpleadoSueldoResponse darDeBaja(Long usuarioId, LocalDate fechaBaja, String motivo) {
        ConfiguracionSueldo c = getConfig(usuarioId);
        LocalDate hoy = LocalDate.now(reloj);
        if (!c.isActivo()) throw new BusinessException(c.getEmpleadoNombre() + " ya está dado de baja.");
        LocalDate baja = fechaBaja != null ? fechaBaja : hoy;
        if (baja.isBefore(c.getFechaAlta())) {
            throw new BusinessException("La baja no puede ser anterior al alta (" + DIA_MES_ANIO.format(c.getFechaAlta()) + ").");
        }
        if (baja.isAfter(hoy)) throw new BusinessException("La fecha de baja no puede ser futura.");

        LocalDate sinCerrar = primerDiaSinCerrar(c);
        LocalDate primeroPosterior = baja.plusDays(1);
        if (primeroPosterior.isAfter(sinCerrar)) {
            // Faltaba cerrar días hasta la baja: se devengan ahora.
            c.setFechaBaja(baja);
            Devengo dev = CalculoDevengo.devengo(c, sinCerrar, baja, marcasEntre(usuarioId, sinCerrar, baja));
            if (dev.diasPagos() > 0) {
                acreditar(c, dev.exacto(), TipoMovimientoSueldo.DEVENGO, baja,
                        "Devengo hasta la baja del " + DIA_MES.format(baja) + " (" + dev.diasPagos() + " días pagos)");
            }
        } else if (primeroPosterior.isBefore(sinCerrar)) {
            // Ya se devengaron días posteriores a la baja: se descuentan (con el empleo todavía vigente para contarlos).
            LocalDate ultimoCerrado = sinCerrar.minusDays(1);
            Devengo dev = CalculoDevengo.devengo(c, primeroPosterior, ultimoCerrado, marcasEntre(usuarioId, primeroPosterior, ultimoCerrado));
            if (dev.diasPagos() > 0) {
                acreditar(c, dev.exacto().negate(), TipoMovimientoSueldo.AJUSTE_ASISTENCIA, baja,
                        "Baja del " + DIA_MES.format(baja) + ", anterior al último cierre: se descuentan "
                                + dev.diasPagos() + " días ya devengados");
            }
            c.setFechaBaja(baja);
        } else {
            c.setFechaBaja(baja);
        }
        c.setUltimoDevengoCalculado(primeroPosterior);
        c.setActivo(false);
        configRepo.save(c);
        auditoria.registrar(CurrentUser.usernameOrSistema(), "SUELDO", "Empleado dado de baja", "Empleado " + c.getEmpleadoNombre(),
                "Último día: " + DIA_MES_ANIO.format(baja) + (motivo != null && !motivo.isBlank() ? " · " + motivo.trim() : ""));
        return EmpleadoSueldoResponse.from(c);
    }

    /**
     * Reincorpora a un empleado dado de baja. {@code desde} es su primer día: el tiempo que estuvo de baja no
     * se devenga (su "alta" pasa a ser ese día).
     */
    @Transactional
    public EmpleadoSueldoResponse reactivar(Long usuarioId, LocalDate desde) {
        ConfiguracionSueldo c = getConfig(usuarioId);
        LocalDate hoy = LocalDate.now(reloj);
        if (c.isActivo()) throw new BusinessException(c.getEmpleadoNombre() + " ya está activo.");
        LocalDate primerDia = desde != null ? desde : hoy;
        if (c.getFechaBaja() != null && !primerDia.isAfter(c.getFechaBaja())) {
            throw new BusinessException("La vuelta tiene que ser posterior a la baja (" + DIA_MES_ANIO.format(c.getFechaBaja()) + ").");
        }
        if (primerDia.isAfter(hoy)) throw new BusinessException("La fecha de vuelta no puede ser futura.");

        c.setActivo(true);
        c.setFechaBaja(null);
        c.setFechaAlta(primerDia);
        c.setUltimoDevengoCalculado(primerDia);
        c.setRestoDevengo(BigDecimal.ZERO);
        configRepo.save(c);
        auditoria.registrar(CurrentUser.usernameOrSistema(), "SUELDO", "Empleado reactivado", "Empleado " + c.getEmpleadoNombre(),
                "Primer día: " + DIA_MES_ANIO.format(primerDia));
        return EmpleadoSueldoResponse.from(c);
    }

    private ConfiguracionSueldo getConfig(Long usuarioId) {
        return configRepo.findByEmpleadoId(usuarioId)
                .orElseThrow(() -> new ResourceNotFoundException("Empleado", usuarioId));
    }
}
