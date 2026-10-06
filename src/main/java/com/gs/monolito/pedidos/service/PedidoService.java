package com.gs.monolito.pedidos.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.common.security.CurrentUser;
import com.gs.monolito.pedidos.dto.EntregaRequest;
import com.gs.monolito.pedidos.dto.PedidoRequest;
import com.gs.monolito.pedidos.dto.PedidoResponse;
import com.gs.monolito.pedidos.exception.BusinessException;
import com.gs.monolito.pedidos.exception.ResourceNotFoundException;
import com.gs.monolito.pedidos.model.EstadoPedido;
import com.gs.monolito.pedidos.model.Odontologo;
import com.gs.monolito.pedidos.model.Pedido;
import com.gs.monolito.pedidos.repository.DocumentoPedidoRepository;
import com.gs.monolito.pedidos.repository.EscaneosPedidoRepository;
import com.gs.monolito.pedidos.repository.OdontologoRepository;
import com.gs.monolito.pedidos.repository.PedidoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Implementación de {@link IPedidoService} para la gestión del ciclo de vida de pedidos.
 *
 * <p>Orquesta: numeración automática, transiciones de estado en el Kanban,
 * descuento automático de stock al entrar en producción (vía
 * {@link ConsumoStockService} — antes por Feign, ahora llamada directa en el
 * mismo proceso), notificación WhatsApp al quedar LISTO, y emisión del
 * comprobante de deuda en finanzas al ENTREGAR (vía
 * {@link EmisionComprobanteService}, ídem).</p>
 */
@Service
@lombok.extern.slf4j.Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PedidoService implements IPedidoService {

    private final PedidoRepository pedidoRepository;
    private final OdontologoRepository odontologoRepository;
    private final IOdontologoService odontologoService;
    private final ConsumoStockService consumoStockService;
    private final NotificacionBotService notificacionBotService;
    private final EmisionComprobanteService emisionComprobanteService;
    private final AuditoriaService auditoria;
    private final DocumentoPedidoRepository documentoRepository;
    private final EscaneosPedidoRepository escaneosRepository;
    private final PedidosDocumentoStorageService documentoStorage;

    @Value("${gs.pedidos.dias-limite-atraso:6}")
    private int diasLimiteAtraso;

    private PedidoResponse toResponse(Pedido p) {
        return PedidoResponse.from(p, diasLimiteAtraso);
    }

    @Override
    public List<PedidoResponse> listarTodos() {
        return pedidoRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public List<PedidoResponse> listarActivos() {
        return pedidoRepository.findByEstadoNot(EstadoPedido.LISTO)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public List<PedidoResponse> listarPorEstado(EstadoPedido estado) {
        return pedidoRepository.findByEstado(estado)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public PedidoResponse buscarPorId(Long id) {
        return pedidoRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido", id));
    }

    @Override
    public List<PedidoResponse> listarAtrasados() {
        return pedidoRepository.findAll().stream()
                .map(this::toResponse)
                .filter(PedidoResponse::atrasado)
                .toList();
    }

    @Override
    @Transactional
    public PedidoResponse crear(PedidoRequest request) {
        Odontologo odontologo = resolverOdontologo(request);

        Pedido pedido = Pedido.builder()
                .nroPedido(generarNroPedido())
                .odontologoId(odontologo.getId())
                .odontologoNombre(odontologo.getNombre())
                .paciente(request.getPaciente())
                .catalogoTrabajoId(request.getCatalogoTrabajoId())
                .trabajo(request.getTrabajo())
                .tecnicoId(request.getTecnicoId())
                .tecnicoNombre(request.getTecnicoNombre())
                .fechaEntrega(request.getFechaEntrega())
                .prioridad(request.getPrioridad())
                .precioAcordado(request.getPrecioAcordado())
                .observaciones(request.getObservaciones())
                .build();

        Pedido guardado = pedidoRepository.save(pedido);
        auditoria.registrar(CurrentUser.usernameOrSistema(), "CREAR", "Pedido creado", "Pedido " + guardado.getNroPedido(),
                "Odontólogo " + guardado.getOdontologoNombre() + " · " + guardado.getTrabajo());
        return toResponse(guardado);
    }

    @Override
    @Transactional
    public PedidoResponse actualizarEstado(Long id, EstadoPedido nuevoEstado) {
        Pedido pedido = pedidoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido", id));

        EstadoPedido estadoAnterior = pedido.getEstado();
        validarTransicion(estadoAnterior, nuevoEstado);
        if (estadoAnterior == nuevoEstado) {
            return toResponse(pedido);
        }
        pedido.setEstado(nuevoEstado);

        // Idempotente (flag stockConsumido): se reintenta en cada movimiento
        // dentro de producción, así un descuento que quedó pendiente (receta con
        // un material que no existe) se completa apenas se corrige la receta.
        if (nuevoEstado.alcanzoProduccion()) {
            consumoStockService.descontarSiCorresponde(pedido);
        }

        Pedido guardado = pedidoRepository.save(pedido);

        if (nuevoEstado == EstadoPedido.LISTO && estadoAnterior != EstadoPedido.LISTO) {
            if (guardado.getNotificadoListoEn() != null) {
                // LISTO -> otro estado -> LISTO (un error de carga): al odontólogo ya se le avisó.
                log.info("[Bot] Pedido {} ya se avisó como listo ({}): no se repite el WhatsApp.",
                        guardado.getNroPedido(), guardado.getNotificadoListoEn());
            } else {
                odontologoRepository.findById(pedido.getOdontologoId()).ifPresent(od ->
                    notificacionBotService.notificarPedidoListo(guardado.getId(), guardado.getNroPedido(), guardado.getTrabajo(), od)
                );
            }
        }

        auditoria.registrar(CurrentUser.usernameOrSistema(), "ESTADO", "Cambio de estado de pedido", "Pedido " + guardado.getNroPedido(),
                estadoAnterior + " → " + nuevoEstado);
        return toResponse(guardado);
    }

    @Override
    @Transactional
    public PedidoResponse actualizar(Long id, PedidoRequest request) {
        Pedido pedido = pedidoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido", id));

        Odontologo odontologo = resolverOdontologo(request);

        boolean precioCambio = pedido.isComprobanteGenerado()
                && request.getPrecioAcordado() != null
                && request.getPrecioAcordado().compareTo(
                        pedido.getPrecioAcordado() != null ? pedido.getPrecioAcordado() : java.math.BigDecimal.ZERO) != 0;

        boolean odontologoCambio = !java.util.Objects.equals(pedido.getOdontologoId(), odontologo.getId());
        if (odontologoCambio
                && !emisionComprobanteService.reasignarOdontologoSiCorresponde(pedido, odontologo.getId(), odontologo.getNombre())) {
            throw new BusinessException("No se puede cambiar el odontólogo: la deuda de este pedido ya tiene pagos "
                    + "imputados a " + pedido.getOdontologoNombre() + ".");
        }
        String odontologoAnterior = pedido.getOdontologoNombre();

        pedido.setOdontologoId(odontologo.getId());
        pedido.setOdontologoNombre(odontologo.getNombre());
        pedido.setPaciente(request.getPaciente());
        pedido.setCatalogoTrabajoId(request.getCatalogoTrabajoId());
        pedido.setTrabajo(request.getTrabajo());
        pedido.setTecnicoId(request.getTecnicoId());
        pedido.setTecnicoNombre(request.getTecnicoNombre());
        pedido.setFechaEntrega(request.getFechaEntrega());
        pedido.setPrioridad(request.getPrioridad());
        pedido.setPrecioAcordado(request.getPrecioAcordado());
        pedido.setObservaciones(request.getObservaciones());

        if (precioCambio) {
            emisionComprobanteService.sincronizarMontoSiCorresponde(pedido, request.getPrecioAcordado());
        }

        Pedido guardado = pedidoRepository.save(pedido);
        auditoria.registrar(CurrentUser.usernameOrSistema(), "EDITAR", "Pedido editado", "Pedido " + guardado.getNroPedido(),
                odontologoCambio ? "Odontólogo " + odontologoAnterior + " → " + guardado.getOdontologoNombre()
                        + (guardado.isComprobanteGenerado() ? " (deuda reasignada)" : "")
                        : "Datos del pedido actualizados");
        return toResponse(guardado);
    }

    @Override
    @Transactional
    public PedidoResponse marcarEntregado(Long id, EntregaRequest request) {
        Pedido pedido = pedidoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido", id));

        if (pedido.getEstado() != EstadoPedido.LISTO) {
            throw new BusinessException(
                "Solo se pueden entregar pedidos en estado LISTO. Estado actual: " + pedido.getEstado());
        }

        pedido.setEstado(EstadoPedido.ENTREGADO);
        pedido.setFechaEntregaReal(
            request.getFechaEntregaReal() != null ? request.getFechaEntregaReal() : LocalDate.now());
        pedido.setRetiradoPor(request.getRetiradoPor().trim());
        pedido.setObservacionesEntrega(
            request.getObservacionesEntrega() != null && !request.getObservacionesEntrega().isBlank()
                ? request.getObservacionesEntrega().trim()
                : null);

        java.math.BigDecimal monto = request.getMonto() != null
            ? request.getMonto()
            : pedido.getPrecioAcordado();
        emisionComprobanteService.emitirSiCorresponde(pedido, monto);

        Pedido guardado = pedidoRepository.save(pedido);
        auditoria.registrar(CurrentUser.usernameOrSistema(), "ENTREGA", "Pedido entregado", "Pedido " + guardado.getNroPedido(),
                "Retiró: " + guardado.getRetiradoPor() + " · facturado $" + (monto != null ? monto : "—"));
        return toResponse(guardado);
    }

    /**
     * Borrado definitivo, solo para pedidos que todavía no movieron nada: sin
     * deuda generada y sin stock descontado. Antes se borraba cualquiera y
     * quedaban huérfanos la deuda del odontólogo, los movimientos de stock y
     * los archivos en MinIO — y el borrado ni siquiera quedaba en la auditoría.
     * Para un pedido que ya avanzó, lo que corresponde es cancelarlo.
     */
    @Override
    @Transactional
    public void eliminar(Long id) {
        Pedido pedido = pedidoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido", id));
        if (pedido.isComprobanteGenerado()) {
            throw new BusinessException("El pedido " + pedido.getNroPedido()
                    + " ya generó deuda al odontólogo: no se puede borrar.");
        }
        if (pedido.isStockConsumido()) {
            throw new BusinessException("El pedido " + pedido.getNroPedido()
                    + " ya descontó stock: cancelalo en vez de borrarlo.");
        }

        List<String> archivos = new java.util.ArrayList<>();
        documentoRepository.findByPedidoIdOrderByFechaSubidaDesc(id).forEach(d -> {
            archivos.add(d.getObjectKey());
            documentoRepository.delete(d);
        });
        escaneosRepository.findByPedidoIdOrderByFechaSubidaDesc(id).forEach(e -> {
            archivos.add(e.getObjectKey());
            escaneosRepository.delete(e);
        });
        pedidoRepository.delete(pedido);
        // MinIO no es transaccional: los archivos se borran recién cuando la
        // base confirmó. Si algo falla queda un archivo suelto, pero nunca un
        // registro apuntando a un archivo que ya no existe.
        Runnable borrarArchivos = () -> archivos.forEach(documentoStorage::eliminar);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    borrarArchivos.run();
                }
            });
        } else {
            borrarArchivos.run();
        }

        auditoria.registrar(CurrentUser.usernameOrSistema(), "ELIMINAR", "Pedido eliminado", "Pedido " + pedido.getNroPedido(),
                "Odontólogo " + pedido.getOdontologoNombre() + " · " + pedido.getTrabajo()
                        + (archivos.isEmpty() ? "" : " · " + archivos.size() + " archivo(s) borrados"));
    }

    /**
     * Reglas del cambio de estado por PATCH (Kanban y botón "Cancelar"):
     * <ul>
     *   <li>Entre RECIBIDO, EN_PROCESO, CONTROL y LISTO: libre, hacia adelante
     *       o hacia atrás (retrabajos).</li>
     *   <li>A CANCELADO: desde cualquiera de esos cuatro.</li>
     *   <li>A ENTREGADO: nunca por acá — solo con "Registrar entrega", que es
     *       lo que genera la deuda del odontólogo. Antes, pasarlo a ENTREGADO
     *       desde el Kanban dejaba el trabajo entregado sin deuda.</li>
     *   <li>Desde ENTREGADO o CANCELADO: nada — son estados finales (un
     *       entregado vuelto atrás o cancelado dejaba su deuda viva).</li>
     * </ul>
     */
    static void validarTransicion(EstadoPedido actual, EstadoPedido nuevo) {
        if (nuevo == null) {
            throw new BusinessException("Falta el estado destino.");
        }
        if (actual == nuevo) return;
        if (actual == EstadoPedido.ENTREGADO || actual == EstadoPedido.CANCELADO) {
            throw new BusinessException("El pedido está " + actual + " y ya no puede cambiar de estado.");
        }
        if (nuevo == EstadoPedido.ENTREGADO) {
            throw new BusinessException(
                "Para entregar un pedido usá \"Registrar entrega\": es lo que genera la deuda del odontólogo.");
        }
    }

    private Odontologo resolverOdontologo(PedidoRequest request) {
        if (request.getOdontologoId() != null) {
            var dto = odontologoService.buscarPorId(request.getOdontologoId());
            return Odontologo.builder()
                    .id(dto.id())
                    .nombre(dto.nombre())
                    .build();
        }
        return odontologoService.buscarOCrearPorNombre(request.getOdontologoNombre());
    }

    /**
     * Genera PED-yyyyMMdd-XXXX siguiendo al último número DEL DÍA, no contando
     * filas de toda la tabla (mismo bug/fix que el número de comprobante en finanzas).
     */
    private String generarNroPedido() {
        String fecha = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String prefijo = String.format("PED-%s-", fecha);
        String ultimo = pedidoRepository.maxNroPedidoConPrefijo(prefijo);

        long siguiente = 1;
        if (ultimo != null && ultimo.length() > prefijo.length()) {
            try {
                siguiente = Long.parseLong(ultimo.substring(prefijo.length())) + 1;
            } catch (NumberFormatException e) {
                siguiente = pedidoRepository.count() + 1;
            }
        }
        return String.format("%s%04d", prefijo, siguiente);
    }
}
