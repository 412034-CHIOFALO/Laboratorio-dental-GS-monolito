package com.gs.monolito.finanzas.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.common.security.CurrentUser;
import com.gs.monolito.finanzas.dto.*;
import com.gs.monolito.finanzas.exception.BusinessException;
import com.gs.monolito.finanzas.exception.ResourceNotFoundException;
import com.gs.monolito.finanzas.model.*;
import com.gs.monolito.finanzas.repository.ConfiguracionSueldoRepository;
import com.gs.monolito.finanzas.repository.PagoSueldoRepository;
import com.gs.monolito.finanzas.repository.ProveedorRepository;
import com.gs.monolito.finanzas.repository.RegistroPagoBotRepository;
import com.gs.monolito.finanzas.repository.ComprobanteRepository;
import com.gs.monolito.finanzas.repository.DeudaProveedorRepository;
import com.gs.monolito.finanzas.repository.CajaMovimientoRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Implementación de {@link IGestionSueldoService} para la gestión de sueldos del personal del laboratorio.
 *
 * <p>El sistema de sueldos funciona con devengo por mes calendario:</p>
 * <ol>
 *   <li>Cada empleado tiene una {@link ConfiguracionSueldo} con
 *       un monto base y una frecuencia de cobro ({@link FrecuenciaPago}), y un calendario de
 *       asistencia (ausencias, vacaciones, feriados).</li>
 *   <li>{@link DevengoService} cierra los días (al arrancar y cada hora, ver {@link DevengoScheduler}),
 *       acumulando en {@code saldoDevengado} lo que ganó cada día según las reglas de {@link CalculoDevengo}.</li>
 *   <li>Al registrar un pago, el monto se descuenta del {@code saldoDevengado}.
 *       El sobrante o faltante se gestiona según la política {@link ManejoSobrante}.</li>
 *   <li>Todo cambio del saldo queda en el libro de sueldos ({@link LibroSueldos}).</li>
 * </ol>
 *
 * <p>También procesa comprobantes de transferencia enviados por el bot de WhatsApp
 * ({@link #registrarPagoAutomatico}), resolviendo si el receptor es empleado o proveedor.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GestionSueldoService implements IGestionSueldoService {

    private static final Logger log = LoggerFactory.getLogger(GestionSueldoService.class);

    /** Un comprobante "cuenta" para detectar repetidos mientras esté registrado o esperando revisión. */
    private static final List<EstadoRegistroBot> ESTADOS_QUE_CUENTAN = List.of(EstadoRegistroBot.REGISTRADO, EstadoRegistroBot.PENDIENTE);
    /** Al aprobar un pendiente solo importa lo que ya se aplicó: otro pendiente igual no bloquea (se aprueba uno solo). */
    private static final List<EstadoRegistroBot> ESTADOS_APLICADOS = List.of(EstadoRegistroBot.REGISTRADO);
    /** Mismo monto y mismo receptor dentro de estas horas = "se parece a otro comprobante" y lo revisa una persona. */
    private static final int HORAS_PARECIDO_TRANSFERENCIA = 72;
    private static final int HORAS_PARECIDO_EFECTIVO = 24;
    /** Id que no existe: para buscar duplicados de un registro que todavía no se guardó. */
    private static final Long SIN_ID = 0L;

    private final ConfiguracionSueldoRepository configRepo;
    private final PagoSueldoRepository pagoRepo;
    private final FinanzasComprobanteStorageService minioStorage;
    private final ProveedorRepository proveedorRepo;
    private final RegistroPagoBotRepository registroRepo;
    private final ComprobanteRepository comprobanteRepo;
    private final DeudaProveedorRepository deudaProveedorRepo;
    private final CajaMovimientoRepository cajaMovimientoRepo;
    private final AuditoriaService auditoria;
    private final RevisionPago revision;
    private final DevengoService devengo;
    private final LibroSueldos libro;

    @Override
    public List<EmpleadoSueldoResponse> listarEmpleados() {
        return configRepo.findAllByOrderByEmpleadoNombreAsc().stream()
                .map(EmpleadoSueldoResponse::from)
                .toList();
    }

    @Override
    public EmpleadoSueldoResponse buscarEmpleado(Long usuarioId) {
        return EmpleadoSueldoResponse.from(getConfig(usuarioId));
    }

    @Override
    @Transactional
    public EmpleadoSueldoResponse guardarConfig(Long usuarioId, ConfigSueldoRequest req) {
        ConfiguracionSueldo c = configRepo.findByEmpleadoId(usuarioId)
                .orElseThrow(() -> new ResourceNotFoundException("Empleado", usuarioId));
        boolean cambiaSueldo = c.getFrecuencia() != req.getFrecuencia()
                || c.getMontoBase().compareTo(req.getMontoBase()) != 0;
        String antes = c.getFrecuencia() + " $" + c.getMontoBase().toPlainString();
        if (cambiaSueldo && c.isActivo()) {
            // Lo trabajado hasta ayer se liquida con el sueldo anterior; el nuevo rige desde hoy.
            devengo.devengarConfig(c, LocalDate.now());
        }
        c.setFrecuencia(req.getFrecuencia());
        c.setMontoBase(req.getMontoBase());
        if (req.getDiasVacacionesAnuales() != null) c.setDiasVacacionesAnuales(req.getDiasVacacionesAnuales());
        EmpleadoSueldoResponse resp = EmpleadoSueldoResponse.from(configRepo.save(c));
        if (cambiaSueldo) {
            auditoria.registrar(CurrentUser.usernameOrSistema(), "SUELDO", "Sueldo modificado", "Empleado " + c.getEmpleadoNombre(),
                    antes + " → " + c.getFrecuencia() + " $" + c.getMontoBase().toPlainString());
        }
        return resp;
    }

    @Override
    @Transactional
    public EmpleadoSueldoResponse crearEmpleado(CrearEmpleadoRequest req) {
        if (configRepo.findByEmpleadoId(req.getUsuarioId()).isPresent()) {
            throw new com.gs.monolito.finanzas.exception.ConflictException("Ese empleado ya está dado de alta en sueldos.");
        }
        ConfiguracionSueldo c = ConfiguracionSueldo.builder()
                .empleadoId(req.getUsuarioId())
                .empleadoNombre(req.getNombre())
                .rol(req.getRol())
                .telefono(req.getTelefono())
                .activo(true)
                .frecuencia(req.getFrecuencia())
                .montoBase(req.getMontoBase())
                .fechaAlta(req.getFechaAlta() != null ? req.getFechaAlta() : LocalDate.now())
                .build();
        if (req.getDiasVacacionesAnuales() != null) c.setDiasVacacionesAnuales(req.getDiasVacacionesAnuales());
        EmpleadoSueldoResponse resp = EmpleadoSueldoResponse.from(configRepo.save(c));
        auditoria.registrar(CurrentUser.usernameOrSistema(), "SUELDO", "Empleado dado de alta en sueldos", "Empleado " + c.getEmpleadoNombre(),
                c.getFrecuencia() + " $" + c.getMontoBase().toPlainString() + " · desde " + c.getFechaAlta());
        return resp;
    }

    @Override
    @Transactional
    public PagoSueldoResponse registrarPago(PagoSueldoRequest req) {
        ConfiguracionSueldo c = getConfig(req.getUsuarioId());

        PagoSueldo pago = aplicarPago(
                c,
                req.getMonto(),
                req.getManejoSobrante() != null ? req.getManejoSobrante() : ManejoSobrante.DESCONTAR_PROXIMO,
                req.getFecha(),
                OrigenPago.MANUAL,
                req.getNota()
        );
        // La plata sale de la caja que corresponde al medio con el que se pagó: antes salía siempre de la Física,
        // aunque el pago fuera una transferencia.
        MedioPago medio = req.getMedio() != null ? req.getMedio() : MedioPago.EFECTIVO;
        TipoCaja caja = medio == MedioPago.TRANSFERENCIA ? TipoCaja.BANCARIA : TipoCaja.FISICA;
        pago.setMedio(medio);
        PagoSueldo guardado = pagoRepo.save(pago);
        // Con la fecha del pago (puede ser anterior a hoy), no la de carga.
        registrarMovimiento(TipoMovimientoCaja.EGRESO, caja, req.getMonto(),
                "Sueldo (" + (medio == MedioPago.TRANSFERENCIA ? "transferencia" : "efectivo") + ") a " + c.getEmpleadoNombre(), null,
                CurrentUser.usernameOrSistema(), req.getFecha());
        // Si se pagó de más y el empleado devuelve la diferencia, esa plata vuelve a la misma caja (el egreso de
        // arriba fue por el monto completo). Con DESCONTAR_PROXIMO o CUBRE_LAB no vuelve nada.
        if (guardado.getManejoSobrante() == ManejoSobrante.DEVUELVE_EMPLEADO) {
            registrarExcedenteEnCaja(guardado, caja, null);
        }
        auditoria.registrar(CurrentUser.usernameOrSistema(), "SUELDO", "Pago de sueldo (manual)", "Empleado " + c.getEmpleadoNombre(),
                "$" + req.getMonto() + (medio == MedioPago.TRANSFERENCIA ? " por transferencia" : " en efectivo"));
        return PagoSueldoResponse.from(guardado);
    }

    /**
     * Procesa un comprobante detectado por el bot de WhatsApp.
     *
     * Clasifica al receptor (Para) y registra el pago según corresponda:
     *   1. EMPLEADO  → pago de sueldo (descuenta del devengado).
     *   2. PROVEEDOR → pago a proveedor.
     *   3. Ninguno   → rechazo.
     *
     * SIEMPRE deja un {@link RegistroPagoBot} (incluso rechazos y duplicados),
     * que es lo que alimenta el historial del bot en el front. Por eso NO lanza
     * excepción ante un rechazo: devuelve el resultado con su estado y mensaje.
     */
    @Override
    @Transactional
    public RegistroPagoBotResponse registrarPagoAutomatico(PagoAutomaticoRequest req) {
        // 0) Idempotencia por mensaje de WhatsApp: si este mensaje (o su pie) ya quedó registrado
        // —en cualquier estado— se devuelve ese mismo registro y NO se crea nada nuevo. Es lo que
        // evita duplicados cuando el bot reprocesa (reconciliación, reinicio, pérdida de su estado).
        Optional<RegistroPagoBot> previo = buscarPorMensaje(req.getIdMensaje(), req.getIdMensajePie());
        if (previo.isPresent()) {
            log.info("[BOT] Mensaje {} ya procesado (registro {}): no se vuelve a registrar.",
                    req.getIdMensaje(), previo.get().getId());
            return RegistroPagoBotResponse.repetido(previo.get());
        }

        RegistroPagoBot reg = RegistroPagoBot.builder()
                .monto(req.getMonto())
                .idOperacion(operacionParaGuardar(req.getIdOperacion()))
                .idMensajeWa(vacioANull(req.getIdMensaje()))
                .idMensajePie(vacioANull(req.getIdMensajePie()))
                .emisor(req.getEmisor())
                .receptorNombre(req.getReceptorNombre())
                .cargadoPorNombre(req.getCargadoPorNombre())
                .cargadoPorTelefono(req.getCargadoPorTelefono())
                .grupoOrigen(req.getGrupoOrigen())
                .build();

        String comprobanteRef = guardarComprobante(req);
        reg.setComprobanteUrl(comprobanteRef);
        String hash = hashDeComprobante(req);
        reg.setHashComprobante(hash);

        // Repetido: el mismo N° de operación o exactamente el mismo archivo que otro comprobante YA REGISTRADO O
        // TODAVÍA PENDIENTE de revisión. Antes solo se miraba contra los registrados: un comprobante reenviado
        // mientras el primero esperaba revisión quedaba también pendiente, y aprobando los dos se pagaba doble.
        Optional<Duplicado> repetido = buscarDuplicado(req.getIdOperacion(), hash, SIN_ID, ESTADOS_QUE_CUENTAN);
        if (repetido.isPresent()) {
            reg.setEstado(EstadoRegistroBot.DUPLICADO);
            reg.setTipoReceptor(TipoReceptorBot.DESCONOCIDO);
            reg.setMensaje(recortar(repetido.get().motivo(), 300));
            log.info("[BOT] Comprobante repetido (registro #{}): {}", repetido.get().otro().getId(), repetido.get().motivo());
            return RegistroPagoBotResponse.from(registroRepo.save(reg));
        }

        // Semáforo: si algo del comprobante no se puede dar por bueno, NO se aplica el pago: queda pendiente
        // de revisión y un administrativo lo aprueba o rechaza viendo la imagen. Solo se frena si el receptor
        // es conocido (si no lo es, se rechaza igual más abajo, no hay nada que revisar).
        List<String> motivos = new java.util.ArrayList<>(revision.motivos(req));
        parecidoA(req).ifPresent(otro -> motivos.add("Se parece a otro comprobante ya cargado (registro #" + otro.getId()
                + ", " + hace(otro.getFechaHora()) + "): mismo monto y mismo receptor"));
        boolean receptorConocido = resolverEmpleadoOpt(req).isPresent() || resolverProveedorOpt(req.getReceptorNombre()).isPresent();
        if (receptorConocido) {
            // Si no se sabe con certeza QUIÉN pagó (un odontólogo con deuda, o dos con nombre parecido), no se descuenta
            // la deuda de nadie solo: lo decide una persona. Sin odontólogo posible, es un pago normal del laboratorio.
            ResolucionEmisor quien = resolverEmisor(req.getEmisor(), req.getReceptorNombre());
            if (quien.tipo() == Resolucion.AMBIGUO) {
                motivos.add("No se sabe seguro quién pagó: «" + req.getEmisor() + "» puede ser "
                        + NombresPersona.enumerar(quien.candidatos().stream().map(CandidatoEmisor::nombre).toList())
                        + ". Elegí cuál fue al aprobar");
            }
        }
        if (!motivos.isEmpty() && receptorConocido) {
            reg.setEstado(EstadoRegistroBot.PENDIENTE);
            reg.setFuente(FuentePago.TRANSFERENCIA);
            reg.setTipoReceptor(TipoReceptorBot.DESCONOCIDO);
            reg.setMotivosRevision(recortar(String.join(" | ", motivos), 600));
            reg.setMensaje("Pendiente de revisión: " + String.join("; ", motivos));
            log.info("[BOT] Transferencia a {} por ${} queda pendiente de revisión: {}", req.getReceptorNombre(), req.getMonto(), motivos);
            auditoria.registrar(CurrentUser.usernameOrSistema(), "REVISION", "Comprobante pendiente de revisión", "Receptor " + req.getReceptorNombre(),
                    "$" + req.getMonto() + " — " + String.join("; ", motivos));
            return RegistroPagoBotResponse.from(registroRepo.save(reg));
        }

        return aplicarTransferencia(reg, req, comprobanteRef, DecisionEmisor.AUTOMATICA);
    }

    // ── Repetidos ────────────────────────────────────────────────────

    /** El comprobante con el que choca un registro, y por qué. */
    private record Duplicado(RegistroPagoBot otro, String motivo) {}

    /**
     * ¿Ya hay otro registro (en alguno de esos estados) con el mismo N° de operación o el mismo archivo?
     * El N° de operación se compara normalizado (sin espacios ni signos, en mayúsculas), porque el OCR
     * puede leer "12 345 678" donde otro comprobante dice "12345678".
     */
    private Optional<Duplicado> buscarDuplicado(String idOperacion, String hash, Long idPropio, List<EstadoRegistroBot> estados) {
        List<String> variantes = variantesDeOperacion(idOperacion);
        if (!variantes.isEmpty()) {
            Optional<RegistroPagoBot> otro = registroRepo.findFirstByIdOperacionInAndEstadoInAndIdNotOrderByIdAsc(variantes, estados, idPropio);
            if (otro.isPresent()) {
                return Optional.of(new Duplicado(otro.get(), "Ya hay un comprobante con esa operación (" + variantes.get(variantes.size() - 1)
                        + "): registro #" + otro.get().getId() + ", " + estadoEnPalabras(otro.get()) + "."));
            }
        }
        if (hash != null) {
            Optional<RegistroPagoBot> otro = registroRepo.findFirstByHashComprobanteAndEstadoInAndIdNotOrderByIdAsc(hash, estados, idPropio);
            if (otro.isPresent()) {
                return Optional.of(new Duplicado(otro.get(), "Ese mismo archivo ya está cargado (aunque no se pudo leer el N° de operación): registro #"
                        + otro.get().getId() + ", " + estadoEnPalabras(otro.get()) + "."));
            }
        }
        return Optional.empty();
    }

    private static String estadoEnPalabras(RegistroPagoBot r) {
        return r.getEstado() == EstadoRegistroBot.PENDIENTE ? "pendiente de revisión" : "ya registrado";
    }

    /** Mismo monto y mismo receptor cargados hace poco, aunque el N° de operación o el archivo no coincidan. */
    private Optional<RegistroPagoBot> parecidoA(PagoAutomaticoRequest req) {
        return parecido(req.getMonto(), req.getReceptorNombre(), FuentePago.TRANSFERENCIA, HORAS_PARECIDO_TRANSFERENCIA);
    }

    private Optional<RegistroPagoBot> efectivoParecidoA(PagoEfectivoRequest req) {
        return parecido(req.getMonto(), req.getReceptorNombre(), FuentePago.EFECTIVO, HORAS_PARECIDO_EFECTIVO);
    }

    private Optional<RegistroPagoBot> parecido(BigDecimal monto, String receptor, FuentePago fuente, int horas) {
        String receptorNorm = normalizarNombre(receptor);
        if (monto == null || receptorNorm.isEmpty()) return Optional.empty();
        return registroRepo.findByMontoAndEstadoInAndFechaHoraAfterAndIdNot(
                        monto, ESTADOS_QUE_CUENTAN, java.time.LocalDateTime.now().minusHours(horas), SIN_ID).stream()
                .filter(r -> r.getFuente() == fuente)
                .filter(r -> receptorNorm.equals(normalizarNombre(r.getReceptorNombre())))
                .findFirst();
    }

    /** "hace 3 h" / "hace 12 min" / "hace 2 días". */
    private static String hace(java.time.LocalDateTime cuando) {
        if (cuando == null) return "hace un rato";
        long min = java.time.Duration.between(cuando, java.time.LocalDateTime.now()).toMinutes();
        if (min < 1) return "recién";
        if (min < 60) return "hace " + min + " min";
        if (min < 48 * 60) return "hace " + (min / 60) + " h";
        return "hace " + (min / (24 * 60)) + " días";
    }

    /** Sin espacios ni signos y en mayúsculas; null si no queda nada. */
    static String normalizarOperacion(String operacion) {
        if (operacion == null) return null;
        String limpia = operacion.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return limpia.isEmpty() ? null : limpia;
    }

    /** Lo que se guarda en el registro: el N° normalizado (así los repetidos se encuentran aunque se lea distinto). */
    private static String operacionParaGuardar(String operacion) {
        String norm = normalizarOperacion(operacion);
        return norm != null ? norm : vacioANull(operacion);
    }

    /** El N° leído y su versión normalizada, para buscar también los registros guardados antes de normalizar. */
    private static List<String> variantesDeOperacion(String operacion) {
        String norm = normalizarOperacion(operacion);
        if (norm == null) return List.of();
        String crudo = operacion.trim();
        return crudo.equals(norm) ? List.of(norm) : List.of(crudo, norm);
    }

    private static String recortar(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /**
     * Aplica una transferencia ya aceptada (directo, o después de que un administrativo la aprobó): sueldo de un
     * empleado o pago a un proveedor, triangulado si el emisor es un odontólogo con deuda. Si el receptor no es ni
     * empleado ni proveedor, queda RECHAZADA.
     */
    private RegistroPagoBotResponse aplicarTransferencia(RegistroPagoBot reg, PagoAutomaticoRequest req, String comprobanteRef,
                                                         DecisionEmisor decision) {
        // 1) ¿Es un empleado? → pago de sueldo
        Optional<ConfiguracionSueldo> empleado = resolverEmpleadoOpt(req);
        if (empleado.isPresent()) {
            ConfiguracionSueldo c = empleado.get();
            // Fuera del try: si no se sabe quién pagó es un error para quien aprueba, no un rechazo del comprobante.
            Optional<CandidatoEmisor> odo = odontologoQuePago(req.getEmisor(), c.getEmpleadoNombre(), decision);
            try {
                ManejoSobrante manejo = odo.isPresent() ? ManejoSobrante.DESCONTAR_PROXIMO : ManejoSobrante.DEVUELVE_EMPLEADO;

                PagoSueldo pago = aplicarPago(c, req.getMonto(), manejo,
                        req.getFecha(), OrigenPago.BOT_WHATSAPP, req.getNota());
                pago.setMedio(MedioPago.TRANSFERENCIA);
                pago.setCargadoPorNombre(req.getCargadoPorNombre());
                pago.setCargadoPorTelefono(req.getCargadoPorTelefono());
                pago.setEmisor(req.getEmisor());
                pago.setGrupoOrigen(req.getGrupoOrigen());
                pago.setIdOperacion(req.getIdOperacion());
                pago.setComprobanteUrl(comprobanteRef);
                pagoRepo.save(pago);

                reg.setEstado(EstadoRegistroBot.REGISTRADO);
                reg.setTipoReceptor(TipoReceptorBot.EMPLEADO);
                reg.setReceptorId(c.getEmpleadoId());
                reg.setReceptorResuelto(c.getEmpleadoNombre());

                if (odo.isPresent()) {
                    CandidatoEmisor oc = odo.get();
                    BigDecimal settOdo = settleDeudaOdontologo(oc.odontologoId(), req.getMonto());
                    registrarMovimiento(TipoMovimientoCaja.INGRESO, TipoCaja.COMPENSACION, req.getMonto(),
                            "Pago directo: " + oc.nombre() + " pagó el sueldo de " + c.getEmpleadoNombre(),
                            req.getIdOperacion());
                    registrarMovimiento(TipoMovimientoCaja.EGRESO, TipoCaja.COMPENSACION, req.getMonto(),
                            "Pago directo: sueldo de " + c.getEmpleadoNombre() + ", pagado por " + oc.nombre(),
                            req.getIdOperacion());
                    reg.setMensaje("Pago directo de " + oc.nombre() + ": sueldo de " + c.getEmpleadoNombre()
                            + ". Se descontaron $" + settOdo.toBigInteger() + " de lo que " + oc.nombre() + " debía");
                    log.info("[BOT] Pago directo de sueldo: {} pagó a {} por {}",
                            oc.nombre(), c.getEmpleadoNombre(), req.getMonto());
                    auditoria.registrar(CurrentUser.usernameOrSistema(), "SUELDO", "Pago de sueldo (bot, pago directo del odontólogo)", "Empleado " + c.getEmpleadoNombre(),
                            oc.nombre() + " pagó $" + req.getMonto() + " por cuenta del laboratorio");
                } else {
                    registrarMovimiento(TipoMovimientoCaja.EGRESO, TipoCaja.BANCARIA, req.getMonto(),
                            "Sueldo (transferencia) a " + c.getEmpleadoNombre(), req.getIdOperacion());
                    reg.setMensaje("Sueldo registrado para " + c.getEmpleadoNombre());
                    log.info("[BOT] Sueldo: {} recibió {} (emisor: {})", c.getEmpleadoNombre(), req.getMonto(), req.getEmisor());
                    auditoria.registrar(CurrentUser.usernameOrSistema(), "SUELDO", "Pago de sueldo (bot)", "Empleado " + c.getEmpleadoNombre(),
                            "Transferencia recibida de " + req.getEmisor() + " por $" + req.getMonto());
                    registrarExcedenteEnCaja(pago, TipoCaja.BANCARIA, req.getIdOperacion());
                }
                return RegistroPagoBotResponse.from(registroRepo.save(reg));
            } catch (BusinessException e) {
                reg.setEstado(EstadoRegistroBot.RECHAZADO);
                reg.setTipoReceptor(TipoReceptorBot.EMPLEADO);
                reg.setReceptorId(c.getEmpleadoId());
                reg.setReceptorResuelto(c.getEmpleadoNombre());
                reg.setMensaje(e.getMessage());
                return RegistroPagoBotResponse.from(registroRepo.save(reg));
            }
        }

        // 2) ¿Es un proveedor? → pago a proveedor (directo o triangulado)
        Optional<Proveedor> proveedor = resolverProveedorOpt(req.getReceptorNombre());
        if (proveedor.isPresent()) {
            Proveedor p = proveedor.get();
            reg.setEstado(EstadoRegistroBot.REGISTRADO);
            reg.setTipoReceptor(TipoReceptorBot.PROVEEDOR);
            reg.setReceptorId(p.getId());
            reg.setReceptorResuelto(p.getNombre());

            Optional<CandidatoEmisor> odo = odontologoQuePago(req.getEmisor(), p.getNombre(), decision);
            if (odo.isPresent()) {
                CandidatoEmisor oc = odo.get();
                BigDecimal settOdo  = settleDeudaOdontologo(oc.odontologoId(), req.getMonto());
                BigDecimal settProv = settleDeudaProveedor(p.getId(), req.getMonto());
                registrarMovimiento(TipoMovimientoCaja.INGRESO, TipoCaja.COMPENSACION, req.getMonto(),
                        "Pago directo: " + oc.nombre() + " pagó a " + p.getNombre(), req.getIdOperacion());
                registrarMovimiento(TipoMovimientoCaja.EGRESO, TipoCaja.COMPENSACION, req.getMonto(),
                        "Pago directo: a proveedor " + p.getNombre() + ", pagado por " + oc.nombre(), req.getIdOperacion());
                reg.setMensaje("Pago directo de " + oc.nombre() + " a " + p.getNombre()
                        + ". Se descontaron $" + settOdo.toBigInteger() + " de la deuda de " + oc.nombre()
                        + " y $" + settProv.toBigInteger() + " de lo que se le debía a " + p.getNombre());
                log.info("[BOT] Pago directo: {} → {} por {}", oc.nombre(), p.getNombre(), req.getMonto());
                auditoria.registrar(CurrentUser.usernameOrSistema(), "PROVEEDOR", "Pago a proveedor (bot, pago directo del odontólogo)", "Proveedor " + p.getNombre(),
                        oc.nombre() + " pagó $" + req.getMonto() + " por cuenta del laboratorio");
                return RegistroPagoBotResponse.from(registroRepo.save(reg));
            }

            BigDecimal settProv = settleDeudaProveedor(p.getId(), req.getMonto());
            registrarMovimiento(TipoMovimientoCaja.EGRESO, TipoCaja.BANCARIA, req.getMonto(),
                    "Pago a proveedor: " + p.getNombre(), req.getIdOperacion());
            reg.setMensaje("Pago a proveedor: " + p.getNombre()
                    + (settProv.signum() > 0 ? " (deuda -$" + settProv.toBigInteger() + ")" : ""));
            log.info("[BOT] Pago a proveedor {}: {} (emisor: {})", p.getNombre(), req.getMonto(), req.getEmisor());
            auditoria.registrar(CurrentUser.usernameOrSistema(), "PROVEEDOR", "Pago a proveedor (bot)", "Proveedor " + p.getNombre(),
                    "Transferencia recibida de " + req.getEmisor() + " por $" + req.getMonto());
            return RegistroPagoBotResponse.from(registroRepo.save(reg));
        }

        // 3) No se reconoció ni empleado ni proveedor
        reg.setEstado(EstadoRegistroBot.RECHAZADO);
        reg.setTipoReceptor(TipoReceptorBot.DESCONOCIDO);
        reg.setMensaje("No se encontró ningún empleado ni proveedor que coincida con \""
                + (req.getReceptorNombre() != null ? req.getReceptorNombre() : "?") + "\"");
        log.info("[BOT] Rechazado: receptor \"{}\" no es empleado ni proveedor", req.getReceptorNombre());
        return RegistroPagoBotResponse.from(registroRepo.save(reg));
    }

    @Override
    @Transactional
    public EmpleadoSueldoResponse ajustarDevengado(Long usuarioId, BigDecimal nuevoDevengado, String motivo) {
        if (nuevoDevengado == null || nuevoDevengado.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("El devengado no puede ser negativo");
        }
        if (motivo == null || motivo.isBlank()) {
            throw new BusinessException("Explicá el motivo del ajuste.");
        }
        ConfiguracionSueldo c = getConfig(usuarioId);
        BigDecimal anterior = c.getSaldoDevengado();
        c.setSaldoDevengado(nuevoDevengado);
        EmpleadoSueldoResponse resp = EmpleadoSueldoResponse.from(configRepo.save(c));
        libro.registrar(c, TipoMovimientoSueldo.AJUSTE_MANUAL, nuevoDevengado.subtract(anterior), LocalDate.now(),
                "Ajuste manual de $" + anterior.toPlainString() + " a $" + nuevoDevengado.toPlainString() + " · " + motivo.trim());
        auditoria.registrar(CurrentUser.usernameOrSistema(), "SUELDO", "Devengado ajustado a mano", "Empleado " + c.getEmpleadoNombre(),
                "$" + anterior.toPlainString() + " → $" + nuevoDevengado.toPlainString() + " · " + motivo.trim());
        return resp;
    }

    @Override
    public List<PagoSueldoResponse> historialPagos(Long usuarioId) {
        return pagoRepo.findByEmpleadoIdOrderByFechaDescIdDesc(usuarioId).stream()
                .map(PagoSueldoResponse::from)
                .toList();
    }

    @Override
    public List<PagoSueldoResponse> historialPagosGlobal() {
        return pagoRepo.findAllByOrderByFechaDescIdDesc().stream()
                .map(PagoSueldoResponse::from)
                .toList();
    }

    @Override
    public List<RegistroPagoBotResponse> listarRegistrosBot() {
        return registroRepo.findAllByOrderByFechaHoraDesc().stream()
                .map(RegistroPagoBotResponse::from)
                .toList();
    }

    @Override
    public BigDecimal totalDevengado() {
        return configRepo.totalDevengado();
    }

    @Override
    public String objectKeyComprobante(Long pagoId) {
        PagoSueldo pago = pagoRepo.findById(pagoId)
                .orElseThrow(() -> new ResourceNotFoundException("Pago", pagoId));
        return validarObjectKey(pago.getComprobanteUrl());
    }

    @Override
    public String objectKeyComprobanteRegistro(Long registroId) {
        RegistroPagoBot r = registroRepo.findById(registroId)
                .orElseThrow(() -> new ResourceNotFoundException("Registro", registroId));
        return validarObjectKey(r.getComprobanteUrl());
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private String validarObjectKey(String objectName) {
        if (objectName == null || objectName.isBlank()) {
            throw new BusinessException("No tiene comprobante guardado");
        }
        return objectName;
    }

    private Optional<RegistroPagoBot> buscarPorMensaje(String... ids) {
        for (String id : ids) {
            if (id == null || id.isBlank()) continue;
            Optional<RegistroPagoBot> r = registroRepo.findFirstByIdMensajeWaOrIdMensajePie(id, id);
            if (r.isPresent()) return r;
        }
        return Optional.empty();
    }

    private static String vacioANull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** SHA-256 (hex) del archivo del comprobante, o null si no vino archivo / no se pudo decodificar. */
    private static String hashDeComprobante(PagoAutomaticoRequest req) {
        if (req.getComprobanteBase64() == null || req.getComprobanteBase64().isBlank()) return null;
        try {
            byte[] datos = java.util.Base64.getDecoder().decode(req.getComprobanteBase64());
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(datos));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> mensajesConocidos(List<String> ids) {
        List<String> limpios = ids.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        if (limpios.isEmpty()) return List.of();
        java.util.Set<String> conocidos = new java.util.LinkedHashSet<>(registroRepo.idsMensajeWaConocidos(limpios));
        conocidos.addAll(registroRepo.idsMensajePieConocidos(limpios));
        return List.copyOf(conocidos);
    }

    private String guardarComprobante(PagoAutomaticoRequest req) {
        String ref = req.getComprobanteUrl();
        if (req.getComprobanteBase64() != null && !req.getComprobanteBase64().isBlank()) {
            try {
                byte[] datos = java.util.Base64.getDecoder().decode(req.getComprobanteBase64());
                String objectName = minioStorage.subir(datos, req.getComprobanteMime(), req.getComprobanteNombre());
                if (objectName != null) ref = objectName;
            } catch (Exception e) {
                log.warn("[BOT] No se pudo guardar el comprobante en MinIO: {}", e.getMessage());
            }
        }
        return ref;
    }

    private Optional<ConfiguracionSueldo> resolverEmpleadoOpt(PagoAutomaticoRequest req) {
        if (req.getReceptorUsuarioId() != null) {
            return configRepo.findByEmpleadoId(req.getReceptorUsuarioId());
        }
        if (req.getReceptorTelefono() != null && !req.getReceptorTelefono().isBlank()) {
            return configRepo.findAllByOrderByEmpleadoNombreAsc().stream()
                    .filter(x -> req.getReceptorTelefono().equals(x.getTelefono()))
                    .findFirst();
        }
        if (req.getReceptorNombre() != null && !req.getReceptorNombre().isBlank()) {
            List<ConfiguracionSueldo> matches = configRepo.findAllByOrderByEmpleadoNombreAsc().stream()
                    .filter(x -> coincideNombre(x.getEmpleadoNombre(), req.getReceptorNombre()))
                    .toList();
            if (matches.size() == 1) return Optional.of(matches.get(0));
        }
        return Optional.empty();
    }

    private Optional<Proveedor> resolverProveedorOpt(String nombre) {
        if (nombre == null || nombre.isBlank()) return Optional.empty();
        List<Proveedor> coinciden = proveedorRepo.findByActivoTrue().stream()
                .filter(p -> coincideNombre(p.getNombre(), nombre))
                .toList();
        if (coinciden.size() == 1) return Optional.of(coinciden.get(0));
        // Antes se tomaba el primero que coincidiera: ante dos proveedores parecidos podía pagarse a otro. Ahora solo
        // vale uno que coincida exacto y sea el único; si hay duda, no se elige al azar (el comprobante se rechaza).
        List<Proveedor> exactos = coinciden.stream()
                .filter(p -> NombresPersona.comparar(p.getNombre(), nombre) == NombresPersona.Parecido.EXACTO)
                .toList();
        return exactos.size() == 1 ? Optional.of(exactos.get(0)) : Optional.empty();
    }

    private static String normalizarNombre(String s) {
        if (s == null) return "";
        String sinAcentos = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return sinAcentos.toLowerCase().trim().replaceAll("\\s+", " ");
    }

    /** Por palabras completas (no por pedazos de texto): una palabra suelta como "de" o "juan" ya no alcanza. */
    private static boolean coincideNombre(String nombreGuardado, String query) {
        return NombresPersona.coincideReceptor(nombreGuardado, query);
    }

    // ── ¿Quién pagó? (odontólogo con deuda) ───────────────────────────

    /** Qué se sabe de quién pagó un comprobante. */
    private enum Resolucion { NINGUNO, UNICO, AMBIGUO }

    private record ResolucionEmisor(Resolucion tipo, CandidatoEmisor elegido, List<CandidatoEmisor> candidatos) {}

    /** Lo que decidió una persona al aprobar. {@code AUTOMATICA} = que el sistema resuelva solo. */
    private record DecisionEmisor(Long odontologoId, boolean ninguno) {
        static final DecisionEmisor AUTOMATICA = new DecisionEmisor(null, false);
        static final DecisionEmisor NINGUN_ODONTOLOGO = new DecisionEmisor(null, true);

        static DecisionEmisor de(ConfirmarRegistroRequest r) {
            if (r == null) return AUTOMATICA;
            if (Boolean.TRUE.equals(r.getSinOdontologo())) return NINGUN_ODONTOLOGO;
            return r.getOdontologoId() != null ? new DecisionEmisor(r.getOdontologoId(), false) : AUTOMATICA;
        }
    }

    /**
     * ¿El emisor es un odontólogo con DEUDA PENDIENTE O PARCIAL? Solo en ese caso tiene sentido un pago directo
     * (triangulado). Se excluye al propio receptor: si una misma persona es a la vez receptor y odontólogo cliente,
     * no se la triangula "contra sí misma".
     *
     * <p>Solo se da por resuelto (UNICO) cuando el nombre coincide EXACTO con un único odontólogo. Si coincide solo
     * en parte (uno tiene palabras de más) o hay dos con el mismo nombre, es AMBIGUO: no se paga solo, lo decide
     * una persona entre los candidatos. Antes se tomaba el primero que compartiera una palabra de más de tres letras
     * y se le saldaba la deuda: "Juan Pérez" pagaba la deuda de "Juan Carlos Gómez".</p>
     */
    private ResolucionEmisor resolverEmisor(String emisor, String receptorNombre) {
        if (emisor == null || emisor.isBlank()) return new ResolucionEmisor(Resolucion.NINGUNO, null, List.of());

        // deuda por odontólogo (solo los que deben algo)
        java.util.Map<Long, CandidatoEmisor> conDeuda = new java.util.LinkedHashMap<>();
        for (Comprobante c : comprobanteRepo.findByEstadoPagoIn(List.of(EstadoPago.PENDIENTE, EstadoPago.PARCIAL))) {
            if (c.getOdontologoId() == null) continue;
            BigDecimal saldo = c.getSaldoPendiente() == null ? BigDecimal.ZERO : c.getSaldoPendiente();
            conDeuda.merge(c.getOdontologoId(),
                    new CandidatoEmisor(c.getOdontologoId(), c.getOdontologoNombre(), saldo),
                    (a, b) -> new CandidatoEmisor(a.odontologoId(), a.nombre(), a.deuda().add(b.deuda())));
        }

        List<CandidatoEmisor> exactos = new ArrayList<>();
        List<CandidatoEmisor> parciales = new ArrayList<>();
        for (CandidatoEmisor o : conDeuda.values()) {
            if (receptorNombre != null && !receptorNombre.isBlank()
                    && NombresPersona.comparar(o.nombre(), receptorNombre) != NombresPersona.Parecido.NINGUNO) {
                continue;   // el propio receptor no es "quien pagó"
            }
            switch (NombresPersona.comparar(o.nombre(), emisor)) {
                case EXACTO -> exactos.add(o);
                case CONTENIDO -> parciales.add(o);
                default -> { }
            }
        }
        if (exactos.size() == 1) return new ResolucionEmisor(Resolucion.UNICO, exactos.get(0), List.of());
        if (exactos.size() > 1) return new ResolucionEmisor(Resolucion.AMBIGUO, null, exactos);
        if (!parciales.isEmpty()) return new ResolucionEmisor(Resolucion.AMBIGUO, null, parciales);
        return new ResolucionEmisor(Resolucion.NINGUNO, null, List.of());
    }

    /** El odontólogo que pagó, según lo que dice el sistema y lo que decidió quien aprobó. */
    private Optional<CandidatoEmisor> odontologoQuePago(String emisor, String receptorNombre, DecisionEmisor decision) {
        if (decision.ninguno()) return Optional.empty();
        ResolucionEmisor r = resolverEmisor(emisor, receptorNombre);
        if (decision.odontologoId() != null) {
            return Optional.of(r.candidatos().stream().filter(c -> c.odontologoId().equals(decision.odontologoId()))
                    .findFirst()
                    .or(() -> Optional.ofNullable(r.elegido()).filter(c -> c.odontologoId().equals(decision.odontologoId())))
                    .orElseThrow(() -> new BusinessException(
                            "El odontólogo elegido no figura entre los posibles para «" + emisor + "» o ya no tiene deuda pendiente.")));
        }
        return switch (r.tipo()) {
            case UNICO -> Optional.of(r.elegido());
            case NINGUNO -> Optional.empty();
            case AMBIGUO -> throw new BusinessException("No se sabe quién pagó: «" + emisor + "» puede ser "
                    + NombresPersona.enumerar(r.candidatos().stream().map(CandidatoEmisor::nombre).toList())
                    + ". Elegí cuál fue, o indicá que no fue un odontólogo.");
        };
    }

    private BigDecimal settleDeudaOdontologo(Long odontologoId, BigDecimal monto) {
        BigDecimal restante = monto, settled = BigDecimal.ZERO;
        List<Comprobante> pend = comprobanteRepo
                .findByOdontologoIdAndEstadoPagoIn(odontologoId, List.of(EstadoPago.PENDIENTE, EstadoPago.PARCIAL))
                .stream().sorted(Comparator.comparing(Comprobante::getFechaEmision)).toList();
        for (Comprobante c : pend) {
            if (restante.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal saldo = c.getSaldoPendiente();
            if (saldo.compareTo(BigDecimal.ZERO) <= 0) continue;
            BigDecimal aplica = restante.min(saldo);

            c.setMontoPagado(c.getMontoPagado().add(aplica));
            if (c.getMontoPagado().compareTo(c.getMonto()) >= 0) {
                c.setEstadoPago(EstadoPago.COBRADO);
                c.setFechaCobro(LocalDate.now());
            } else {
                c.setEstadoPago(EstadoPago.PARCIAL);
            }
            comprobanteRepo.save(c);

            restante = restante.subtract(aplica);
            settled = settled.add(aplica);
        }
        return settled;
    }

    private BigDecimal settleDeudaProveedor(Long proveedorId, BigDecimal monto) {
        BigDecimal restante = monto, settled = BigDecimal.ZERO;
        List<DeudaProveedor> pend = deudaProveedorRepo.findByProveedorIdAndEstadoInOrderByFechaCreacionAsc(
                proveedorId, List.of(EstadoDeuda.PENDIENTE, EstadoDeuda.PARCIAL));
        for (DeudaProveedor d : pend) {
            if (restante.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal saldo = d.getSaldoPendiente();
            if (saldo.compareTo(BigDecimal.ZERO) <= 0) continue;
            BigDecimal aplica = restante.min(saldo);

            d.setMontoPagado(d.getMontoPagado().add(aplica));
            if (d.getMontoPagado().compareTo(d.getMonto()) >= 0) {
                d.setEstado(EstadoDeuda.PAGADO);
                d.setFechaPago(LocalDate.now());
            } else {
                d.setEstado(EstadoDeuda.PARCIAL);
            }
            deudaProveedorRepo.save(d);

            restante = restante.subtract(aplica);
            settled = settled.add(aplica);
        }
        return settled;
    }

    private void registrarExcedenteEnCaja(PagoSueldo pago, TipoCaja caja, String referencia) {
        BigDecimal exc = pago.getMontoExcedente();
        if (exc == null || exc.signum() <= 0) return;
        registrarMovimiento(TipoMovimientoCaja.INGRESO, caja, exc,
                "Excedente de sueldo devuelto por " + pago.getEmpleadoNombre(), referencia);
        log.info("[SUELDOS] Excedente de ${} de {} vuelve a la caja {}",
                exc, pago.getEmpleadoNombre(), caja);
    }

    private void registrarMovimiento(TipoMovimientoCaja tipo, TipoCaja caja, BigDecimal monto, String concepto, String ref) {
        registrarMovimiento(tipo, caja, monto, concepto, ref, "bot");
    }

    private void registrarMovimiento(TipoMovimientoCaja tipo, TipoCaja caja, BigDecimal monto, String concepto, String ref, String creadoPor) {
        registrarMovimiento(tipo, caja, monto, concepto, ref, creadoPor, null);
    }

    /** @param fecha fecha del movimiento; null = hoy (default de CajaMovimiento). */
    private void registrarMovimiento(TipoMovimientoCaja tipo, TipoCaja caja, BigDecimal monto, String concepto,
                                     String ref, String creadoPor, LocalDate fecha) {
        cajaMovimientoRepo.save(CajaMovimiento.builder()
                .tipo(tipo).tipoCaja(caja).monto(monto)
                .concepto(concepto).referencia(ref).creadoPor(creadoPor)
                .fechaMovimiento(fecha).build());
    }

    @Override
    @Transactional
    public PagoTrianguladoProveedorResponse registrarPagoTrianguladoProveedor(Long odontologoId, PagoTrianguladoProveedorRequest req) {
        Proveedor p = proveedorRepo.findById(req.proveedorId())
                .orElseThrow(() -> new ResourceNotFoundException("Proveedor", req.proveedorId()));

        String odontologoNombre = comprobanteRepo
                .findByOdontologoIdAndEstadoPagoIn(odontologoId, List.of(EstadoPago.PENDIENTE, EstadoPago.PARCIAL))
                .stream().findFirst().map(Comprobante::getOdontologoNombre)
                .orElse("Odontólogo #" + odontologoId);

        BigDecimal settOdo  = settleDeudaOdontologo(odontologoId, req.monto());
        BigDecimal settProv = settleDeudaProveedor(p.getId(), req.monto());

        String ref = "manual-odo" + odontologoId + "-" + System.currentTimeMillis();
        String notaSufijo = (req.nota() != null && !req.nota().isBlank()) ? " · " + req.nota() : "";
        registrarMovimiento(TipoMovimientoCaja.INGRESO, TipoCaja.COMPENSACION, req.monto(),
                "Pago directo (cargado a mano): " + odontologoNombre + " pagó a " + p.getNombre() + notaSufijo, ref, "panel");
        registrarMovimiento(TipoMovimientoCaja.EGRESO, TipoCaja.COMPENSACION, req.monto(),
                "Pago directo (cargado a mano): a proveedor " + p.getNombre() + ", pagado por " + odontologoNombre + notaSufijo, ref, "panel");

        String mensajeLog = "Pago directo (cargado a mano) de " + odontologoNombre + " a " + p.getNombre()
                + ". Se descontaron $" + settOdo.toBigInteger() + " de la deuda de " + odontologoNombre
                + " y $" + settProv.toBigInteger() + " de lo que se le debía a " + p.getNombre() + notaSufijo;
        RegistroPagoBot reg = RegistroPagoBot.builder()
                .monto(req.monto())
                .emisor(odontologoNombre)
                .receptorNombre(p.getNombre())
                .tipoReceptor(TipoReceptorBot.PROVEEDOR)
                .receptorId(p.getId())
                .receptorResuelto(p.getNombre())
                .estado(EstadoRegistroBot.REGISTRADO)
                .mensaje(mensajeLog)
                .build();
        registroRepo.save(reg);

        auditoria.registrar(CurrentUser.usernameOrSistema(), "PROVEEDOR", "Pago a proveedor (pago directo del odontólogo, a mano)", "Proveedor " + p.getNombre(),
                odontologoNombre + " pagó $" + req.monto() + " por cuenta del laboratorio" + notaSufijo);

        String mensajeUi = "Se imputaron $" + settOdo + " a la cuenta del odontólogo y $" + settProv
                + " a la deuda con " + p.getNombre() + ".";
        return new PagoTrianguladoProveedorResponse(odontologoId, p.getId(), p.getNombre(), req.monto(), settOdo, settProv, mensajeUi);
    }

    // ── Lógica común de aplicación de un pago de sueldo ──────────────

    private PagoSueldo aplicarPago(ConfiguracionSueldo c, BigDecimal monto,
                                   ManejoSobrante manejo, LocalDate fecha,
                                   OrigenPago origen, String nota) {
        if (monto == null || monto.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("El monto debe ser mayor a cero");
        }
        // Quien se dio de baja pero todavía tiene saldo se puede seguir pagando (liquidación final); sin saldo, no.
        if (!c.isActivo() && c.getSaldoDevengado().signum() <= 0) {
            throw new BusinessException("El empleado " + c.getEmpleadoNombre() + " está dado de baja y no tiene saldo pendiente");
        }

        BigDecimal excedente = BigDecimal.ZERO;
        BigDecimal restante = c.getSaldoDevengado().subtract(monto);

        if (restante.compareTo(BigDecimal.ZERO) >= 0) {
            c.setSaldoDevengado(restante);
        } else {
            excedente = restante.abs();
            c.setSaldoDevengado(BigDecimal.ZERO);
            if (manejo == ManejoSobrante.DESCONTAR_PROXIMO) {
                c.setSaldoSobrante(c.getSaldoSobrante().add(excedente));
            }
        }

        LocalDate fechaPago = fecha != null ? fecha : LocalDate.now();
        c.setUltimoPago(fechaPago);
        configRepo.save(c);
        libro.registrar(c, TipoMovimientoSueldo.PAGO, monto.negate(), fechaPago,
                "Pago de $" + monto.toPlainString() + " (" + origen + ")"
                        + (excedente.signum() > 0 ? " · $" + excedente.toPlainString() + " de más (" + manejo + ")" : ""));

        return PagoSueldo.builder()
                .empleadoId(c.getEmpleadoId())
                .empleadoNombre(c.getEmpleadoNombre())
                .monto(monto)
                .fecha(fechaPago)
                .origen(origen)
                .manejoSobrante(manejo)
                .montoExcedente(excedente)
                .nota(nota)
                .build();
    }

    // ── Efectivo (borrador + confirmación manual) ─────────────────────

    @Override
    @Transactional
    public RegistroPagoBotResponse registrarPagoEfectivo(PagoEfectivoRequest req) {
        // Idempotencia por mensaje de WhatsApp (el efectivo no tiene N° de operación ni archivo
        // para detectar un duplicado: el ID del mensaje es la única clave confiable).
        Optional<RegistroPagoBot> previo = buscarPorMensaje(req.getIdMensaje());
        if (previo.isPresent()) {
            log.info("[BOT-EFECTIVO] Mensaje {} ya procesado (registro {}): no se vuelve a crear.",
                    req.getIdMensaje(), previo.get().getId());
            return RegistroPagoBotResponse.repetido(previo.get());
        }
        RegistroPagoBot reg = RegistroPagoBot.builder()
                .idMensajeWa(vacioANull(req.getIdMensaje()))
                .monto(req.getMonto())
                .emisor(req.getEmisor())
                .receptorNombre(req.getReceptorNombre())
                .cargadoPorNombre(req.getCargadoPorNombre())
                .cargadoPorTelefono(req.getCargadoPorTelefono())
                .grupoOrigen(req.getGrupoOrigen())
                .estado(EstadoRegistroBot.PENDIENTE)
                .fuente(FuentePago.EFECTIVO)
                .tipoReceptor(TipoReceptorBot.DESCONOCIDO)
                .mensaje("Efectivo declarado en el grupo — pendiente de confirmación.")
                .build();
        efectivoParecidoA(req).ifPresent(otro -> reg.setMensaje(recortar(
                "Efectivo declarado en el grupo — pendiente de confirmación. Ojo: se parece al registro #" + otro.getId()
                        + " (mismo monto y receptor, " + hace(otro.getFechaHora()) + "); confirmá solo si es otro pago.", 300)));
        log.info("[BOT-EFECTIVO] Borrador: {} → ${}", req.getReceptorNombre(), req.getMonto());
        return RegistroPagoBotResponse.from(registroRepo.save(reg));
    }

    @Override
    @Transactional
    public RegistroPagoBotResponse confirmarEfectivo(Long registroId, ConfirmarRegistroRequest decisionReq) {
        RegistroPagoBot reg = registroRepo.findById(registroId)
                .orElseThrow(() -> new ResourceNotFoundException("Registro", registroId));
        if (reg.getEstado() != EstadoRegistroBot.PENDIENTE) {
            throw new BusinessException("Solo se pueden confirmar registros en estado PENDIENTE");
        }
        DecisionEmisor decision = DecisionEmisor.de(decisionReq);
        if (reg.getFuente() == FuentePago.TRANSFERENCIA) {
            return confirmarTransferencia(reg, decision);
        }

        // 1) Empleado
        Optional<ConfiguracionSueldo> empleado = resolverEmpleadoPorNombre(reg.getReceptorNombre());
        if (empleado.isPresent()) {
            ConfiguracionSueldo c = empleado.get();
            // Fuera del try: si no se sabe quién pagó, quien aprueba tiene que elegir; no es un rechazo del registro.
            Optional<CandidatoEmisor> odoEf = odontologoQuePago(reg.getEmisor(), c.getEmpleadoNombre(), decision);
            try {
                ManejoSobrante manejo = odoEf.isPresent() ? ManejoSobrante.DESCONTAR_PROXIMO : ManejoSobrante.DEVUELVE_EMPLEADO;

                PagoSueldo pago = aplicarPago(c, reg.getMonto(), manejo,
                        LocalDate.now(), OrigenPago.BOT_WHATSAPP, "Efectivo confirmado");
                pago.setMedio(MedioPago.EFECTIVO);
                pago.setCargadoPorNombre(reg.getCargadoPorNombre());
                pago.setCargadoPorTelefono(reg.getCargadoPorTelefono());
                pago.setGrupoOrigen(reg.getGrupoOrigen());
                pagoRepo.save(pago);
                reg.setEstado(EstadoRegistroBot.REGISTRADO);
                reg.setTipoReceptor(TipoReceptorBot.EMPLEADO);
                reg.setReceptorId(c.getEmpleadoId());
                reg.setReceptorResuelto(c.getEmpleadoNombre());

                if (odoEf.isPresent()) {
                    CandidatoEmisor oc = odoEf.get();
                    BigDecimal settOdo = settleDeudaOdontologo(oc.odontologoId(), reg.getMonto());
                    registrarMovimiento(TipoMovimientoCaja.INGRESO, TipoCaja.COMPENSACION, reg.getMonto(),
                            "Pago directo (efectivo): " + oc.nombre() + " pagó el sueldo de " + c.getEmpleadoNombre(), null);
                    registrarMovimiento(TipoMovimientoCaja.EGRESO, TipoCaja.COMPENSACION, reg.getMonto(),
                            "Pago directo (efectivo): sueldo de " + c.getEmpleadoNombre() + ", pagado por " + oc.nombre(), null);
                    reg.setMensaje("Pago directo de " + oc.nombre() + ": sueldo de " + c.getEmpleadoNombre()
                            + ". Se descontaron $" + settOdo.toBigInteger() + " de lo que " + oc.nombre() + " debía");
                    log.info("[BOT-EFECTIVO] Pago directo de sueldo: {} pagó a {} por ${}",
                            oc.nombre(), c.getEmpleadoNombre(), reg.getMonto());
                    auditoria.registrar(CurrentUser.usernameOrSistema(), "SUELDO", "Pago de sueldo (efectivo, pago directo del odontólogo)", "Empleado " + c.getEmpleadoNombre(),
                            oc.nombre() + " pagó $" + reg.getMonto() + " en efectivo por cuenta del laboratorio");
                } else {
                    registrarMovimiento(TipoMovimientoCaja.EGRESO, TipoCaja.FISICA, reg.getMonto(),
                            "Efectivo confirmado: sueldo a " + c.getEmpleadoNombre(), null);
                    reg.setMensaje("Efectivo confirmado: sueldo para " + c.getEmpleadoNombre());
                    log.info("[BOT-EFECTIVO] Confirmado: {} recibió ${} en efectivo", c.getEmpleadoNombre(), reg.getMonto());
                    auditoria.registrar(CurrentUser.usernameOrSistema(), "SUELDO", "Pago de sueldo (efectivo)", "Empleado " + c.getEmpleadoNombre(),
                            "Efectivo confirmado por $" + reg.getMonto() + " (emisor: " + reg.getEmisor() + ")");
                    registrarExcedenteEnCaja(pago, TipoCaja.FISICA, null);
                }
            } catch (BusinessException e) {
                reg.setEstado(EstadoRegistroBot.RECHAZADO);
                reg.setTipoReceptor(TipoReceptorBot.EMPLEADO);
                reg.setMensaje(e.getMessage());
            }
            return RegistroPagoBotResponse.from(registroRepo.save(reg));
        }

        // 2) Proveedor (directo, o triangulado si el emisor es un odontólogo)
        Optional<Proveedor> proveedor = resolverProveedorOpt(reg.getReceptorNombre());
        if (proveedor.isPresent()) {
            Proveedor p = proveedor.get();
            reg.setEstado(EstadoRegistroBot.REGISTRADO);
            reg.setTipoReceptor(TipoReceptorBot.PROVEEDOR);
            reg.setReceptorId(p.getId());
            reg.setReceptorResuelto(p.getNombre());

            Optional<CandidatoEmisor> odo = odontologoQuePago(reg.getEmisor(), p.getNombre(), decision);
            if (odo.isPresent()) {
                CandidatoEmisor oc = odo.get();
                BigDecimal settOdo  = settleDeudaOdontologo(oc.odontologoId(), reg.getMonto());
                BigDecimal settProv = settleDeudaProveedor(p.getId(), reg.getMonto());
                registrarMovimiento(TipoMovimientoCaja.INGRESO, TipoCaja.COMPENSACION, reg.getMonto(),
                        "Pago directo (efectivo): " + oc.nombre() + " pagó a " + p.getNombre(), null);
                registrarMovimiento(TipoMovimientoCaja.EGRESO, TipoCaja.COMPENSACION, reg.getMonto(),
                        "Pago directo (efectivo): a proveedor " + p.getNombre() + ", pagado por " + oc.nombre(), null);
                reg.setMensaje("Pago directo de " + oc.nombre() + " a " + p.getNombre()
                        + ". Se descontaron $" + settOdo.toBigInteger() + " de la deuda de " + oc.nombre()
                        + " y $" + settProv.toBigInteger() + " de lo que se le debía a " + p.getNombre());
                log.info("[BOT-EFECTIVO] Pago directo: {} → {} por ${}", oc.nombre(), p.getNombre(), reg.getMonto());
                auditoria.registrar(CurrentUser.usernameOrSistema(), "PROVEEDOR", "Pago a proveedor (efectivo, pago directo del odontólogo)", "Proveedor " + p.getNombre(),
                        oc.nombre() + " pagó $" + reg.getMonto() + " en efectivo por cuenta del laboratorio");
                return RegistroPagoBotResponse.from(registroRepo.save(reg));
            }

            BigDecimal settled = settleDeudaProveedor(p.getId(), reg.getMonto());
            registrarMovimiento(TipoMovimientoCaja.EGRESO, TipoCaja.FISICA, reg.getMonto(),
                    "Efectivo a proveedor: " + p.getNombre(), null);
            reg.setMensaje("Efectivo a proveedor: " + p.getNombre()
                    + (settled.signum() > 0 ? " (deuda -$" + settled.toBigInteger() + ")" : ""));
            log.info("[BOT-EFECTIVO] Confirmado: proveedor {} recibió ${}", p.getNombre(), reg.getMonto());
            auditoria.registrar(CurrentUser.usernameOrSistema(), "PROVEEDOR", "Pago a proveedor (efectivo)", "Proveedor " + p.getNombre(),
                    "Efectivo confirmado por $" + reg.getMonto() + " (emisor: " + reg.getEmisor() + ")");
            return RegistroPagoBotResponse.from(registroRepo.save(reg));
        }

        // 3) No reconocido
        reg.setEstado(EstadoRegistroBot.RECHAZADO);
        reg.setTipoReceptor(TipoReceptorBot.DESCONOCIDO);
        reg.setMensaje("No se encontró empleado ni proveedor que coincida con \""
                + reg.getReceptorNombre() + "\"");
        return RegistroPagoBotResponse.from(registroRepo.save(reg));
    }

    @Override
    @Transactional
    public RegistroPagoBotResponse rechazarEfectivo(Long registroId, String motivo) {
        RegistroPagoBot reg = registroRepo.findById(registroId)
                .orElseThrow(() -> new ResourceNotFoundException("Registro", registroId));
        if (reg.getEstado() != EstadoRegistroBot.PENDIENTE) {
            throw new BusinessException("El registro no está en estado PENDIENTE");
        }
        reg.setEstado(EstadoRegistroBot.RECHAZADO);
        reg.setMensaje("Rechazado: " + (motivo != null && !motivo.isBlank() ? motivo : "sin motivo"));
        return RegistroPagoBotResponse.from(registroRepo.save(reg));
    }

    @Override
    public List<RegistroPagoBotResponse> listarPendientesEfectivo() {
        return registroRepo.findByEstadoAndFuenteOrderByFechaHoraDesc(EstadoRegistroBot.PENDIENTE, FuentePago.EFECTIVO).stream()
                .map(this::conCandidatos)
                .toList();
    }

    @Override
    public List<RegistroPagoBotResponse> listarPendientesRevision() {
        return registroRepo.findByEstadoAndFuenteOrderByFechaHoraDesc(EstadoRegistroBot.PENDIENTE, FuentePago.TRANSFERENCIA).stream()
                .map(this::conCandidatos)
                .toList();
    }

    /** Si no se sabe con certeza quién pagó, el pendiente trae los posibles para que quien aprueba elija. */
    private RegistroPagoBotResponse conCandidatos(RegistroPagoBot reg) {
        RegistroPagoBotResponse base = RegistroPagoBotResponse.from(reg);
        ResolucionEmisor quien = resolverEmisor(reg.getEmisor(), reg.getReceptorNombre());
        return quien.tipo() == Resolucion.AMBIGUO ? base.conCandidatos(quien.candidatos()) : base;
    }

    /** Un administrativo aprobó una transferencia que el semáforo había frenado: se aplica como si hubiera entrado limpia. */
    private RegistroPagoBotResponse confirmarTransferencia(RegistroPagoBot reg, DecisionEmisor decision) {
        // Entre que quedó pendiente y ahora, otro comprobante igual pudo haberse aplicado (por ejemplo, el que se
        // aprobó primero): se vuelve a mirar antes de pagar. Si lo hay, no se aplica nada.
        Optional<Duplicado> repetido = buscarDuplicado(reg.getIdOperacion(), reg.getHashComprobante(), reg.getId(), ESTADOS_APLICADOS);
        if (repetido.isPresent()) {
            throw new BusinessException("No se puede aprobar: " + repetido.get().motivo() + " Rechazá este comprobante: es repetido.");
        }
        PagoAutomaticoRequest req = new PagoAutomaticoRequest();
        req.setReceptorNombre(reg.getReceptorNombre());
        req.setMonto(reg.getMonto());
        req.setEmisor(reg.getEmisor());
        req.setIdOperacion(reg.getIdOperacion());
        req.setCargadoPorNombre(reg.getCargadoPorNombre());
        req.setCargadoPorTelefono(reg.getCargadoPorTelefono());
        req.setGrupoOrigen(reg.getGrupoOrigen());
        req.setNota("Aprobado tras revisión");
        auditoria.registrar(CurrentUser.usernameOrSistema(), "REVISION", "Comprobante aprobado", "Receptor " + reg.getReceptorNombre(),
                "$" + reg.getMonto() + " — motivos de revisión: " + reg.getMotivosRevision());
        return aplicarTransferencia(reg, req, reg.getComprobanteUrl(), decision);
    }

    @Override
    public DistribucionCascadaResponse sugerirCascada(BigDecimal monto, TipoCaja cajaRemanente) {
        // Incluye a quien se dio de baja pero todavía tiene saldo: sigue siendo plata que se le debe.
        List<ConfiguracionSueldo> empleados = configRepo.findBySaldoDevengadoGreaterThanOrderByEmpleadoNombreAsc(BigDecimal.ZERO);

        BigDecimal restante = monto;
        List<LineaDistribucionResponse> lineas = new ArrayList<>();
        for (ConfiguracionSueldo emp : empleados) {
            if (restante.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal pendiente = emp.getSaldoDevengado();
            if (pendiente == null || pendiente.compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal asignado = pendiente.min(restante);
            lineas.add(new LineaDistribucionResponse(emp.getEmpleadoId(), emp.getEmpleadoNombre(), asignado));
            restante = restante.subtract(asignado);
        }

        return new DistribucionCascadaResponse(lineas, restante, cajaRemanente);
    }

    private Optional<ConfiguracionSueldo> resolverEmpleadoPorNombre(String nombre) {
        if (nombre == null || nombre.isBlank()) return Optional.empty();
        List<ConfiguracionSueldo> matches = configRepo.findAllByOrderByEmpleadoNombreAsc().stream()
                .filter(x -> coincideNombre(x.getEmpleadoNombre(), nombre))
                .toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    private ConfiguracionSueldo getConfig(Long usuarioId) {
        return configRepo.findByEmpleadoId(usuarioId)
                .orElseThrow(() -> new ResourceNotFoundException("Empleado", usuarioId));
    }
}
