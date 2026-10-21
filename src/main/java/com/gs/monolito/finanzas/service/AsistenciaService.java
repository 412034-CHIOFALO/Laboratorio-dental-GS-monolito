package com.gs.monolito.finanzas.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.common.security.CurrentUser;
import com.gs.monolito.finanzas.dto.AsistenciaMesResponse;
import com.gs.monolito.finanzas.dto.AsistenciaMesResponse.Dia;
import com.gs.monolito.finanzas.dto.AsistenciaMesResponse.Resumen;
import com.gs.monolito.finanzas.dto.MarcarAsistenciaResponse;
import com.gs.monolito.finanzas.dto.MovimientoSueldoResponse;
import com.gs.monolito.finanzas.exception.BusinessException;
import com.gs.monolito.finanzas.exception.ResourceNotFoundException;
import com.gs.monolito.finanzas.model.AsistenciaEmpleado;
import com.gs.monolito.finanzas.model.ConfiguracionSueldo;
import com.gs.monolito.finanzas.model.FrecuenciaPago;
import com.gs.monolito.finanzas.model.TipoDia;
import com.gs.monolito.finanzas.model.TipoMovimientoSueldo;
import com.gs.monolito.finanzas.repository.AsistenciaEmpleadoRepository;
import com.gs.monolito.finanzas.repository.ConfiguracionSueldoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * El calendario de asistencia de cada empleado: qué días trabajó, faltó, tomó vacaciones, etc.
 *
 * <p>Cargar o corregir un día que <b>todavía no se devengó</b> (hoy o futuro) no toca el saldo: el próximo
 * cierre ya lo cuenta. Cargar o corregir un día <b>ya devengado</b> corrige el saldo en el acto, por la
 * diferencia, y deja la línea en el libro. Ejemplo: se avisa el miércoles que el lunes faltó → se descuenta lo
 * que valía ese lunes.</p>
 */
@Service
@RequiredArgsConstructor
public class AsistenciaService {

    private static final DateTimeFormatter DIA_MES = DateTimeFormatter.ofPattern("dd/MM");
    private static final int MAX_DIAS_ADELANTE = 366;

    private final ConfiguracionSueldoRepository configRepo;
    private final AsistenciaEmpleadoRepository asistenciaRepo;
    private final DevengoService devengo;
    private final LibroSueldos libro;
    private final AuditoriaService auditoria;
    private final Clock reloj;

    // ── Lectura ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public AsistenciaMesResponse mes(Long usuarioId, YearMonth mes) {
        ConfiguracionSueldo c = getConfig(usuarioId);
        LocalDate primero = mes.atDay(1);
        LocalDate ultimo = mes.atEndOfMonth();
        Map<LocalDate, TipoDia> marcas = devengo.marcasEntre(usuarioId, primero, ultimo);
        FrecuenciaPago f = c.getFrecuencia();

        List<Dia> dias = new ArrayList<>();
        Map<TipoDia, Integer> porTipo = new EnumMap<>(TipoDia.class);
        int pagos = 0;
        int noPagos = 0;
        BigDecimal sueldo = BigDecimal.ZERO;
        for (LocalDate d = primero; !d.isAfter(ultimo); d = d.plusDays(1)) {
            TipoDia marca = marcas.get(d);
            boolean enRelacion = !d.isBefore(c.getFechaAlta()) && (c.getFechaBaja() == null || !d.isAfter(c.getFechaBaja()));
            boolean sePaga = CalculoDevengo.sePaga(f, marca, d, c.getFechaAlta(), c.getFechaBaja());
            dias.add(new Dia(d, marca, sePaga, enRelacion));
            if (marca != null && enRelacion) porTipo.merge(marca, 1, Integer::sum);
            if (!enRelacion) continue;
            if (sePaga) {
                pagos++;
                sueldo = sueldo.add(CalculoDevengo.tarifaDelDia(f, c.getMontoBase(), d));
            } else {
                noPagos++;
            }
        }

        int tomadas = vacacionesDelAnio(usuarioId, mes.getYear());
        int anuales = c.getDiasVacacionesAnuales();
        Resumen resumen = new Resumen(pagos, noPagos,
                porTipo.getOrDefault(TipoDia.TRABAJADO, 0),
                porTipo.getOrDefault(TipoDia.AUSENTE, 0),
                porTipo.getOrDefault(TipoDia.VACACIONES, 0),
                porTipo.getOrDefault(TipoDia.FERIADO, 0),
                porTipo.getOrDefault(TipoDia.LICENCIA_PAGA, 0),
                sueldo.setScale(2, RoundingMode.HALF_UP),
                anuales, tomadas, Math.max(0, anuales - tomadas));
        return new AsistenciaMesResponse(mes.toString(), c.getEmpleadoId(), c.getEmpleadoNombre(), f,
                c.getFechaAlta(), c.getFechaBaja(), dias, resumen);
    }

    @Transactional(readOnly = true)
    public List<MovimientoSueldoResponse> movimientos(Long usuarioId, int limite) {
        getConfig(usuarioId);
        return libro.ultimos(usuarioId, limite).stream().map(MovimientoSueldoResponse::from).toList();
    }

    // ── Escritura ────────────────────────────────────────────────────

    /**
     * Marca (o desmarca, con {@code tipo == null}) varios días de un empleado de una vez.
     *
     * @return cuántos días cambiaron de verdad y cuánto se corrigió el saldo
     */
    @Transactional
    public MarcarAsistenciaResponse marcar(Long usuarioId, Collection<LocalDate> fechas, TipoDia tipo, String nota) {
        ConfiguracionSueldo c = getConfig(usuarioId);
        LocalDate hoy = LocalDate.now(reloj);
        TreeSet<LocalDate> dias = new TreeSet<>(fechas);
        if (dias.isEmpty()) throw new BusinessException("Elegí al menos un día.");
        validar(c, dias, hoy);

        Map<LocalDate, AsistenciaEmpleado> actuales = asistenciaRepo
                .findByEmpleadoIdAndFechaBetween(usuarioId, dias.first(), dias.last()).stream()
                .collect(Collectors.toMap(AsistenciaEmpleado::getFecha, a -> a, (a, b) -> a, HashMap::new));

        FrecuenciaPago f = c.getFrecuencia();
        String usuario = CurrentUser.usernameOrSistema();
        String notaLimpia = (nota == null || nota.isBlank()) ? null : nota.trim();
        BigDecimal diferencia = BigDecimal.ZERO;
        List<LocalDate> cambiados = new ArrayList<>();

        for (LocalDate d : dias) {
            AsistenciaEmpleado actual = actuales.get(d);
            TipoDia antes = actual == null ? null : actual.getTipo();
            if (antes == tipo) continue;

            if (tipo == null) {
                asistenciaRepo.delete(actual);
            } else if (actual == null) {
                asistenciaRepo.save(AsistenciaEmpleado.builder()
                        .empleadoId(usuarioId).fecha(d).tipo(tipo).nota(notaLimpia).cargadoPor(usuario).build());
            } else {
                actual.setTipo(tipo);
                actual.setNota(notaLimpia);
                actual.setCargadoPor(usuario);
                asistenciaRepo.save(actual);
            }
            cambiados.add(d);

            // Solo un día YA devengado mueve el saldo ahora; los demás los cuenta el próximo cierre.
            if (yaDevengado(c, d)) {
                boolean pagabaAntes = CalculoDevengo.sePaga(f, antes, d, c.getFechaAlta(), c.getFechaBaja());
                boolean pagaAhora = CalculoDevengo.sePaga(f, tipo, d, c.getFechaAlta(), c.getFechaBaja());
                if (pagabaAntes != pagaAhora) {
                    BigDecimal valor = CalculoDevengo.tarifaDelDia(f, c.getMontoBase(), d);
                    diferencia = pagaAhora ? diferencia.add(valor) : diferencia.subtract(valor);
                }
            }
        }

        BigDecimal ajuste = BigDecimal.ZERO;
        if (!cambiados.isEmpty()) {
            if (diferencia.signum() != 0) {
                ajuste = devengo.acreditar(c, diferencia, TipoMovimientoSueldo.AJUSTE_ASISTENCIA, hoy,
                        "Asistencia corregida: " + descripcion(tipo, cambiados));
                configRepo.save(c);
            }
            auditoria.registrar(usuario, "SUELDO", "Asistencia cargada", "Empleado " + c.getEmpleadoNombre(),
                    descripcion(tipo, cambiados) + (ajuste.signum() != 0 ? " · saldo " + (ajuste.signum() > 0 ? "+" : "") + "$" + ajuste.toPlainString() : ""));
        }

        List<String> advertencias = new ArrayList<>();
        if (tipo == TipoDia.VACACIONES && !cambiados.isEmpty()) {
            int anio = cambiados.get(0).getYear();
            asistenciaRepo.flush();
            int tomadas = vacacionesDelAnio(usuarioId, anio);
            if (tomadas > c.getDiasVacacionesAnuales()) {
                advertencias.add("Con esto suma " + tomadas + " días de vacaciones en " + anio
                        + " y le corresponden " + c.getDiasVacacionesAnuales() + ".");
            }
        }
        return new MarcarAsistenciaResponse(cambiados.size(), ajuste, c.getSaldoDevengado(), advertencias);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void validar(ConfiguracionSueldo c, TreeSet<LocalDate> dias, LocalDate hoy) {
        if (dias.first().isBefore(c.getFechaAlta())) {
            throw new BusinessException("No se puede marcar un día anterior al alta del empleado ("
                    + c.getFechaAlta().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) + ").");
        }
        if (c.getFechaBaja() != null && dias.last().isAfter(c.getFechaBaja())) {
            throw new BusinessException("No se puede marcar un día posterior a la baja ("
                    + c.getFechaBaja().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) + ").");
        }
        if (dias.last().isAfter(hoy.plusDays(MAX_DIAS_ADELANTE))) {
            throw new BusinessException("No se pueden cargar días con más de un año de anticipación.");
        }
    }

    /** ¿El devengo de ese día ya se cerró (y por lo tanto ya está en el saldo)? */
    static boolean yaDevengado(ConfiguracionSueldo c, LocalDate dia) {
        LocalDate ultimo = c.getUltimoDevengoCalculado();
        return ultimo != null && dia.isBefore(ultimo) && !dia.isBefore(c.getFechaAlta());
    }

    private int vacacionesDelAnio(Long usuarioId, int anio) {
        return (int) asistenciaRepo.countByEmpleadoIdAndTipoAndFechaBetween(
                usuarioId, TipoDia.VACACIONES, LocalDate.of(anio, 1, 1), LocalDate.of(anio, 12, 31));
    }

    /** "3 días como AUSENTE (12/10, 13/10, 14/10)" / "2 días sin marca (...)". */
    private static String descripcion(TipoDia tipo, List<LocalDate> dias) {
        String que = tipo == null ? "sin marca" : "como " + tipo.name();
        String lista = dias.stream().limit(6).map(DIA_MES::format).collect(Collectors.joining(", "))
                + (dias.size() > 6 ? "…" : "");
        return dias.size() + (dias.size() == 1 ? " día " : " días ") + que + " (" + lista + ")";
    }

    private ConfiguracionSueldo getConfig(Long usuarioId) {
        return configRepo.findByEmpleadoId(usuarioId)
                .orElseThrow(() -> new ResourceNotFoundException("Empleado", usuarioId));
    }
}
