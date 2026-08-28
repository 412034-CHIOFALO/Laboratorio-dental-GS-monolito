package com.gs.monolito.pedidos.service;

import com.gs.monolito.finanzas.dto.ComprobanteRequest;
import com.gs.monolito.finanzas.service.IFinanzasService;
import com.gs.monolito.pedidos.model.Pedido;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Genera la cuenta por cobrar (comprobante de deuda) en el módulo finanzas
 * cuando un pedido se entrega. La entrega crea la <b>deuda</b>, no el cobro:
 * el odontólogo paga después.
 *
 * <p>Best-effort, preservado igual que con Feign: si el módulo finanzas
 * lanza una excepción, la entrega NO se bloquea; el comprobante queda sin
 * generar y es reintentable (el flag {@code comprobanteGenerado} sigue en
 * false).</p>
 */
@Service
@RequiredArgsConstructor
public class EmisionComprobanteService {

    private static final Logger log = LoggerFactory.getLogger(EmisionComprobanteService.class);

    private final IFinanzasService finanzasService;

    private static final int DIAS_VENCIMIENTO = 30;

    public void emitirSiCorresponde(Pedido pedido, BigDecimal monto) {
        if (pedido.isComprobanteGenerado()) {
            log.debug("[COMPROBANTE] Pedido {} ya tenía comprobante. Skip.", pedido.getNroPedido());
            return;
        }
        if (monto == null || monto.signum() <= 0) {
            log.warn("[COMPROBANTE] Pedido {} sin monto a facturar — no se genera la deuda.",
                    pedido.getNroPedido());
            return;
        }
        try {
            ComprobanteRequest req = new ComprobanteRequest();
            req.setPedidoId(pedido.getId());
            req.setNroPedido(pedido.getNroPedido());
            req.setOdontologoId(pedido.getOdontologoId());
            req.setOdontologoNombre(pedido.getOdontologoNombre());
            req.setTrabajo(pedido.getTrabajo());
            req.setMonto(monto);
            req.setFechaEmision(LocalDate.now());
            req.setFechaVencimiento(LocalDate.now().plusDays(DIAS_VENCIMIENTO));
            req.setObservaciones("Deuda generada al entregar el pedido " + pedido.getNroPedido());
            finanzasService.emitir(req);
            pedido.setComprobanteGenerado(true);
            log.info("[COMPROBANTE] Pedido {} entregado → deuda de ${} a {}",
                    pedido.getNroPedido(), monto, pedido.getOdontologoNombre());
        } catch (Exception e) {
            log.warn("[COMPROBANTE] No se pudo generar el comprobante del pedido {}: {} (reintentable)",
                    pedido.getNroPedido(), e.getMessage());
        }
    }

    /**
     * Sincroniza el monto del comprobante cuando se edita "monto a facturar" de
     * un pedido YA entregado. No hace nada si el pedido no tiene comprobante
     * todavía. Best-effort: si finanzas lanza una excepción, no bloquea la
     * edición del pedido.
     */
    public void sincronizarMontoSiCorresponde(Pedido pedido, BigDecimal nuevoMonto) {
        if (!pedido.isComprobanteGenerado() || nuevoMonto == null || nuevoMonto.signum() <= 0) {
            return;
        }
        try {
            finanzasService.actualizarMontoPorPedido(pedido.getId(), nuevoMonto);
            log.info("[COMPROBANTE] Pedido {} — monto de facturación corregido a ${}",
                    pedido.getNroPedido(), nuevoMonto);
        } catch (Exception e) {
            log.warn("[COMPROBANTE] No se pudo sincronizar el nuevo monto del pedido {}: {} (reintentable)",
                    pedido.getNroPedido(), e.getMessage());
        }
    }
}
