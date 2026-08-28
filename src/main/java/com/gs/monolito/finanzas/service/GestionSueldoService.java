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
 * <p>El sistema de sueldos funciona con devengo diario:</p>
 * <ol>
 *   <li>Cada empleado tiene una {@link ConfiguracionSueldo} con
 *       un monto base y una frecuencia de cobro ({@link FrecuenciaPago}).</li>
 *   <li>El scheduler {@link DevengoScheduler} ejecuta {@link #devengarDiario()} cada noche,
 *       acumulando en {@code saldoDevengado} la parte proporcional del día.</li>
 *   <li>Al registrar un pago, el monto se descuenta del {@code saldoDevengado}.
 *       El sobrante o faltante se gestiona según la política {@link ManejoSobrante}.</li>
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

    private final ConfiguracionSueldoRepository configRepo;
    private final PagoSueldoRepository pagoRepo;
    private final FinanzasComprobanteStorageService minioStorage;
    private final ProveedorRepository proveedorRepo;
    private final RegistroPagoBotRepository registroRepo;
    private final ComprobanteRepository comprobanteRepo;
    private final DeudaProveedorRepository deudaProveedorRepo;
    private final CajaMovimientoRepository cajaMovimientoRepo;
    private final AuditoriaService auditoria;

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
        c.setFrecuencia(req.getFrecuencia());
        c.setMontoBase(req.getMontoBase());
        return EmpleadoSueldoResponse.from(configRepo.save(c));
    }

    @Override
    @Transactional
    public void devengarDiario() {
        LocalDate hoy = LocalDate.now();
        for (ConfiguracionSueldo c : configRepo.findByActivoTrueOrderByEmpleadoNombreAsc()) {
            LocalDate desde = c.getUltimoDevengoCalculado() != null
                    ? c.getUltimoDevengoCalculado()
                    : c.getFechaCreacion().toLocalDate();
            long dias = java.time.temporal.ChronoUnit.DAYS.between(desde, hoy);
            if (dias <= 0) continue; // ya está al día (o el reloj del server se movió para atrás)

            BigDecimal tarifaDiaria = c.getMontoBase()
                    .divide(BigDecimal.valueOf(c.getFrecuencia().diasDeCiclo()), 2, java.math.RoundingMode.HALF_UP);
            BigDecimal devengo = tarifaDiaria.multiply(BigDecimal.valueOf(dias));

            BigDecimal sobrante = c.getSaldoSobrante();
            if (sobrante.signum() > 0) {
                BigDecimal consumido = devengo.min(sobrante);
                c.setSaldoSobrante(sobrante.subtract(consumido));
                devengo = devengo.subtract(consumido);
                log.info("[Devengo] {} — ${} del devengo de hoy se descontó del adelanto pendiente (queda ${} de adelanto)",
                        c.getEmpleadoNombre(), consumido, c.getSaldoSobrante());
            }

            c.setSaldoDevengado(c.getSaldoDevengado().add(devengo));
            c.setUltimoDevengoCalculado(hoy);
            configRepo.save(c);
            log.info("[Devengo] {} — +{} días × ${} = ${} devengado neto (saldo devengado ahora: ${})",
                    c.getEmpleadoNombre(), dias, tarifaDiaria, devengo, c.getSaldoDevengado());
        }
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
                .build();
        return EmpleadoSueldoResponse.from(configRepo.save(c));
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
        PagoSueldo guardado = pagoRepo.save(pago);
        registrarMovimiento(TipoMovimientoCaja.EGRESO, TipoCaja.FISICA, req.getMonto(),
                "Sueldo (manual) a " + c.getEmpleadoNombre(), null);
        auditoria.registrar(CurrentUser.usernameOrSistema(), "SUELDO", "Pago de sueldo (manual)", "Empleado " + c.getEmpleadoNombre(),
                "$" + req.getMonto());
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
        RegistroPagoBot reg = RegistroPagoBot.builder()
                .monto(req.getMonto())
                .idOperacion(req.getIdOperacion())
                .emisor(req.getEmisor())
                .receptorNombre(req.getReceptorNombre())
                .cargadoPorNombre(req.getCargadoPorNombre())
                .cargadoPorTelefono(req.getCargadoPorTelefono())
                .grupoOrigen(req.getGrupoOrigen())
                .build();

        String comprobanteRef = guardarComprobante(req);
        reg.setComprobanteUrl(comprobanteRef);

        if (req.getIdOperacion() != null && !req.getIdOperacion().isBlank()
                && registroRepo.existsByIdOperacionAndEstado(req.getIdOperacion(), EstadoRegistroBot.REGISTRADO)) {
            reg.setEstado(EstadoRegistroBot.DUPLICADO);
            reg.setTipoReceptor(TipoReceptorBot.DESCONOCIDO);
            reg.setMensaje("El comprobante (operación " + req.getIdOperacion() + ") ya fue registrado antes.");
            return RegistroPagoBotResponse.from(registroRepo.save(reg));
        }

        // 1) ¿Es un empleado? → pago de sueldo
        Optional<ConfiguracionSueldo> empleado = resolverEmpleadoOpt(req);
        if (empleado.isPresent()) {
            ConfiguracionSueldo c = empleado.get();
            try {
                Optional<Comprobante> odo = resolverOdontologoEmisor(req.getEmisor(), c.getEmpleadoNombre());
                ManejoSobrante manejo = odo.isPresent() ? ManejoSobrante.DESCONTAR_PROXIMO : ManejoSobrante.DEVUELVE_EMPLEADO;

                PagoSueldo pago = aplicarPago(c, req.getMonto(), manejo,
                        req.getFecha(), OrigenPago.BOT_WHATSAPP, req.getNota());
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
                    Comprobante oc = odo.get();
                    BigDecimal settOdo = settleDeudaOdontologo(oc.getOdontologoId(), req.getMonto());
                    registrarMovimiento(TipoMovimientoCaja.INGRESO, TipoCaja.COMPENSACION, req.getMonto(),
                            "Triangulado: " + oc.getOdontologoNombre() + " paga el sueldo de " + c.getEmpleadoNombre(),
                            req.getIdOperacion());
                    registrarMovimiento(TipoMovimientoCaja.EGRESO, TipoCaja.COMPENSACION, req.getMonto(),
                            "Triangulado: sueldo a " + c.getEmpleadoNombre() + " por cuenta de " + oc.getOdontologoNombre(),
                            req.getIdOperacion());
                    reg.setMensaje("Triangulado: " + oc.getOdontologoNombre() + " → sueldo de " + c.getEmpleadoNombre()
                            + " (odontólogo -$" + settOdo.toBigInteger() + ")");
                    log.info("[BOT] Triangulado sueldo: {} pagó a {} por {}",
                            oc.getOdontologoNombre(), c.getEmpleadoNombre(), req.getMonto());
                    auditoria.registrar(CurrentUser.usernameOrSistema(), "SUELDO", "Pago de sueldo (bot, triangulado)", "Empleado " + c.getEmpleadoNombre(),
                            oc.getOdontologoNombre() + " pagó $" + req.getMonto() + " por cuenta del laboratorio");
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

            Optional<Comprobante> odo = resolverOdontologoEmisor(req.getEmisor(), p.getNombre());
            if (odo.isPresent()) {
                Comprobante oc = odo.get();
                BigDecimal settOdo  = settleDeudaOdontologo(oc.getOdontologoId(), req.getMonto());
                BigDecimal settProv = settleDeudaProveedor(p.getId(), req.getMonto());
                registrarMovimiento(TipoMovimientoCaja.INGRESO, TipoCaja.COMPENSACION, req.getMonto(),
                        "Triangulado: " + oc.getOdontologoNombre() + " paga a " + p.getNombre(), req.getIdOperacion());
                registrarMovimiento(TipoMovimientoCaja.EGRESO, TipoCaja.COMPENSACION, req.getMonto(),
                        "Triangulado: a proveedor " + p.getNombre() + " por cuenta de " + oc.getOdontologoNombre(), req.getIdOperacion());
                reg.setMensaje("Triangulado: " + oc.getOdontologoNombre() + " → " + p.getNombre()
                        + " (odontólogo -$" + settOdo.toBigInteger() + ", proveedor -$" + settProv.toBigInteger() + ")");
                log.info("[BOT] Triangulado: {} → {} por {}", oc.getOdontologoNombre(), p.getNombre(), req.getMonto());
                auditoria.registrar(CurrentUser.usernameOrSistema(), "PROVEEDOR", "Pago a proveedor (bot, triangulado)", "Proveedor " + p.getNombre(),
                        oc.getOdontologoNombre() + " pagó $" + req.getMonto() + " por cuenta del laboratorio");
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
    public EmpleadoSueldoResponse ajustarDevengado(Long usuarioId, BigDecimal nuevoDevengado) {
        if (nuevoDevengado == null || nuevoDevengado.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("El devengado no puede ser negativo");
        }
        ConfiguracionSueldo c = getConfig(usuarioId);
        c.setSaldoDevengado(nuevoDevengado);
        return EmpleadoSueldoResponse.from(configRepo.save(c));
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
        return proveedorRepo.findByActivoTrue().stream()
                .filter(p -> coincideNombre(p.getNombre(), nombre))
                .findFirst();
    }

    private static String normalizarNombre(String s) {
        if (s == null) return "";
        String sinAcentos = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return sinAcentos.toLowerCase().trim().replaceAll("\\s+", " ");
    }

    private static boolean coincideNombre(String nombreGuardado, String query) {
        String nombreNorm = normalizarNombre(nombreGuardado);
        if (nombreNorm.isEmpty()) return false;
        for (String palabra : normalizarNombre(query).split(" ")) {
            if (!palabra.isBlank() && !nombreNorm.contains(palabra)) return false;
        }
        return true;
    }

    /**
     * ¿El emisor es un odontólogo con DEUDA PENDIENTE O PARCIAL? Solo en ese caso
     * tiene sentido un triangulado. Se excluye explícitamente al propio receptor:
     * si una misma persona es a la vez receptor y odontólogo cliente, no se la
     * triangula "contra sí misma".
     */
    private Optional<Comprobante> resolverOdontologoEmisor(String emisor, String receptorNombre) {
        if (emisor == null || emisor.isBlank()) return Optional.empty();
        String[] palabras = emisor.trim().toLowerCase().split("\\s+");
        String prov = receptorNombre == null ? "" : receptorNombre.trim().toLowerCase();
        return comprobanteRepo.findAll().stream()
                .filter(c -> c.getEstadoPago() == EstadoPago.PENDIENTE || c.getEstadoPago() == EstadoPago.PARCIAL)
                .filter(c -> {
                    String nom = c.getOdontologoNombre() == null ? "" : c.getOdontologoNombre().toLowerCase();
                    if (!prov.isBlank() && nom.contains(prov)) return false;
                    for (String w : palabras) if (w.length() > 3 && nom.contains(w)) return true;
                    return false;
                })
                .findFirst();
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
        cajaMovimientoRepo.save(CajaMovimiento.builder()
                .tipo(tipo).tipoCaja(caja).monto(monto)
                .concepto(concepto).referencia(ref).creadoPor(creadoPor).build());
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
                "Triangulado manual: " + odontologoNombre + " paga a " + p.getNombre() + notaSufijo, ref, "panel");
        registrarMovimiento(TipoMovimientoCaja.EGRESO, TipoCaja.COMPENSACION, req.monto(),
                "Triangulado manual: a proveedor " + p.getNombre() + " por cuenta de " + odontologoNombre + notaSufijo, ref, "panel");

        String mensajeLog = "Triangulado manual: " + odontologoNombre + " → " + p.getNombre()
                + " (odontólogo -$" + settOdo.toBigInteger() + ", proveedor -$" + settProv.toBigInteger() + ")" + notaSufijo;
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

        auditoria.registrar(CurrentUser.usernameOrSistema(), "PROVEEDOR", "Pago a proveedor (triangulado manual)", "Proveedor " + p.getNombre(),
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
        if (!c.isActivo()) {
            throw new BusinessException("El empleado " + c.getEmpleadoNombre() + " está inactivo");
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
        RegistroPagoBot reg = RegistroPagoBot.builder()
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
        log.info("[BOT-EFECTIVO] Borrador: {} → ${}", req.getReceptorNombre(), req.getMonto());
        return RegistroPagoBotResponse.from(registroRepo.save(reg));
    }

    @Override
    @Transactional
    public RegistroPagoBotResponse confirmarEfectivo(Long registroId) {
        RegistroPagoBot reg = registroRepo.findById(registroId)
                .orElseThrow(() -> new ResourceNotFoundException("Registro", registroId));
        if (reg.getEstado() != EstadoRegistroBot.PENDIENTE || reg.getFuente() != FuentePago.EFECTIVO) {
            throw new BusinessException("Solo se pueden confirmar registros de efectivo en estado PENDIENTE");
        }

        // 1) Empleado
        Optional<ConfiguracionSueldo> empleado = resolverEmpleadoPorNombre(reg.getReceptorNombre());
        if (empleado.isPresent()) {
            ConfiguracionSueldo c = empleado.get();
            try {
                Optional<Comprobante> odoEf = resolverOdontologoEmisor(reg.getEmisor(), c.getEmpleadoNombre());
                ManejoSobrante manejo = odoEf.isPresent() ? ManejoSobrante.DESCONTAR_PROXIMO : ManejoSobrante.DEVUELVE_EMPLEADO;

                PagoSueldo pago = aplicarPago(c, reg.getMonto(), manejo,
                        LocalDate.now(), OrigenPago.BOT_WHATSAPP, "Efectivo confirmado");
                pago.setCargadoPorNombre(reg.getCargadoPorNombre());
                pago.setCargadoPorTelefono(reg.getCargadoPorTelefono());
                pago.setGrupoOrigen(reg.getGrupoOrigen());
                pagoRepo.save(pago);
                reg.setEstado(EstadoRegistroBot.REGISTRADO);
                reg.setTipoReceptor(TipoReceptorBot.EMPLEADO);
                reg.setReceptorId(c.getEmpleadoId());
                reg.setReceptorResuelto(c.getEmpleadoNombre());

                if (odoEf.isPresent()) {
                    Comprobante oc = odoEf.get();
                    BigDecimal settOdo = settleDeudaOdontologo(oc.getOdontologoId(), reg.getMonto());
                    registrarMovimiento(TipoMovimientoCaja.INGRESO, TipoCaja.COMPENSACION, reg.getMonto(),
                            "Triangulado (efectivo): " + oc.getOdontologoNombre() + " paga el sueldo de " + c.getEmpleadoNombre(), null);
                    registrarMovimiento(TipoMovimientoCaja.EGRESO, TipoCaja.COMPENSACION, reg.getMonto(),
                            "Triangulado (efectivo): sueldo a " + c.getEmpleadoNombre() + " por cuenta de " + oc.getOdontologoNombre(), null);
                    reg.setMensaje("Triangulado: " + oc.getOdontologoNombre() + " → sueldo de " + c.getEmpleadoNombre()
                            + " (odontólogo -$" + settOdo.toBigInteger() + ")");
                    log.info("[BOT-EFECTIVO] Triangulado sueldo: {} pagó a {} por ${}",
                            oc.getOdontologoNombre(), c.getEmpleadoNombre(), reg.getMonto());
                    auditoria.registrar(CurrentUser.usernameOrSistema(), "SUELDO", "Pago de sueldo (efectivo, triangulado)", "Empleado " + c.getEmpleadoNombre(),
                            oc.getOdontologoNombre() + " pagó $" + reg.getMonto() + " en efectivo por cuenta del laboratorio");
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

            Optional<Comprobante> odo = resolverOdontologoEmisor(reg.getEmisor(), p.getNombre());
            if (odo.isPresent()) {
                Comprobante oc = odo.get();
                BigDecimal settOdo  = settleDeudaOdontologo(oc.getOdontologoId(), reg.getMonto());
                BigDecimal settProv = settleDeudaProveedor(p.getId(), reg.getMonto());
                registrarMovimiento(TipoMovimientoCaja.INGRESO, TipoCaja.COMPENSACION, reg.getMonto(),
                        "Triangulado (efectivo): " + oc.getOdontologoNombre() + " paga a " + p.getNombre(), null);
                registrarMovimiento(TipoMovimientoCaja.EGRESO, TipoCaja.COMPENSACION, reg.getMonto(),
                        "Triangulado (efectivo): a proveedor " + p.getNombre() + " por cuenta de " + oc.getOdontologoNombre(), null);
                reg.setMensaje("Triangulado: " + oc.getOdontologoNombre() + " → " + p.getNombre()
                        + " (odontólogo -$" + settOdo.toBigInteger() + ", proveedor -$" + settProv.toBigInteger() + ")");
                log.info("[BOT-EFECTIVO] Triangulado: {} → {} por ${}", oc.getOdontologoNombre(), p.getNombre(), reg.getMonto());
                auditoria.registrar(CurrentUser.usernameOrSistema(), "PROVEEDOR", "Pago a proveedor (efectivo, triangulado)", "Proveedor " + p.getNombre(),
                        oc.getOdontologoNombre() + " pagó $" + reg.getMonto() + " en efectivo por cuenta del laboratorio");
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
        return registroRepo.findByEstadoOrderByFechaHoraDesc(EstadoRegistroBot.PENDIENTE).stream()
                .map(RegistroPagoBotResponse::from)
                .toList();
    }

    @Override
    public DistribucionCascadaResponse sugerirCascada(BigDecimal monto, TipoCaja cajaRemanente) {
        List<ConfiguracionSueldo> empleados = configRepo.findByActivoTrueOrderByEmpleadoNombreAsc();

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
