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
import com.gs.monolito.pedidos.repository.OdontologoRepository;
import com.gs.monolito.pedidos.repository.PedidoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        pedido.setEstado(nuevoEstado);

        if (nuevoEstado.alcanzoProduccion() && !estadoAnterior.alcanzoProduccion()) {
            consumoStockService.descontarSiCorresponde(pedido);
        }

        Pedido guardado = pedidoRepository.save(pedido);

        if (nuevoEstado == EstadoPedido.LISTO && estadoAnterior != EstadoPedido.LISTO) {
            odontologoRepository.findById(pedido.getOdontologoId()).ifPresent(od ->
                notificacionBotService.notificarPedidoListo(guardado.getNroPedido(), guardado.getTrabajo(), od)
            );
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

        return toResponse(pedidoRepository.save(pedido));
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

    @Override
    @Transactional
    public void eliminar(Long id) {
        if (!pedidoRepository.existsById(id)) {
            throw new ResourceNotFoundException("Pedido", id);
        }
        pedidoRepository.deleteById(id);
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
