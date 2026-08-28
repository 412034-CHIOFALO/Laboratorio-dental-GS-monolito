package com.gs.monolito.pedidos.service;

import com.gs.monolito.catalogo.dto.IngredienteRecetaResponse;
import com.gs.monolito.catalogo.dto.TipoTrabajoResponse;
import com.gs.monolito.catalogo.service.ITipoTrabajoService;
import com.gs.monolito.pedidos.model.Pedido;
import com.gs.monolito.stock.dto.MovimientoRequest;
import com.gs.monolito.stock.model.TipoMovimiento;
import com.gs.monolito.stock.service.IStockService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Orquesta el descuento automático de stock cuando un pedido entra en producción.
 *
 * Flujo:
 *   1. Pedido alcanza o supera EN_PROCESO (lo dispara PedidoService.actualizarEstado,
 *      incluso si el salto en el Kanban va directo de RECIBIDO a una columna
 *      posterior — ver EstadoPedido.alcanzoProduccion())
 *   2. Si el pedido tiene catalogoTrabajoId y stockConsumido = false:
 *      a. Pide la receta del trabajo al módulo catalogo (antes vía Feign,
 *         ahora una llamada directa en el mismo proceso)
 *      b. Por cada ingrediente, llama al módulo stock (SALIDA) con motivo
 *         "Producción pedido P-XXX" (antes vía Feign, ahora directo)
 *   3. Marca el pedido como stockConsumido = true
 *
 * Comportamiento ante errores — preservado exactamente igual que con Feign,
 * a propósito: el try/catch generico de acá no distinguía errores de red de
 * errores de negocio, así que una excepción real lanzada por catalogo/stock
 * (no solo una caída de red, que ya no puede pasar en el mismo proceso) se
 * sigue tragando igual — la operación primaria (cambio de estado del pedido)
 * nunca debe verse afectada por un fallo del descuento de stock, accesorio.
 */
@Service
@RequiredArgsConstructor
public class ConsumoStockService {

    private static final Logger log = LoggerFactory.getLogger(ConsumoStockService.class);

    private final ITipoTrabajoService catalogoService;
    private final IStockService stockService;

    /**
     * Descuenta del stock todos los materiales de la receta del pedido.
     * Idempotente: si ya se consumió, no hace nada.
     *
     * @return true si se consumió (o ya estaba consumido); false si hubo
     *         algún problema serio y conviene reintentar manualmente.
     */
    public boolean descontarSiCorresponde(Pedido pedido) {
        if (pedido.isStockConsumido()) {
            log.debug("[CONSUMO-STOCK] Pedido {} ya tenía stock consumido. Skip.", pedido.getNroPedido());
            return true;
        }
        if (pedido.getCatalogoTrabajoId() == null) {
            log.info("[CONSUMO-STOCK] Pedido {} no tiene catalogoTrabajoId (trabajo custom). " +
                    "Skip descuento automático.", pedido.getNroPedido());
            return true; // no hay nada que descontar — caso "trabajo custom"
        }

        // 1. Pedir la receta al módulo catalogo
        TipoTrabajoResponse trabajo;
        try {
            trabajo = catalogoService.buscarPorId(pedido.getCatalogoTrabajoId());
        } catch (Exception e) {
            log.warn("[CONSUMO-STOCK] No se pudo obtener la receta del trabajo {} desde catalogo: {}. " +
                    "Pedido {} continúa sin descuento automático.",
                    pedido.getCatalogoTrabajoId(), e.getMessage(), pedido.getNroPedido());
            return false;
        }

        List<IngredienteRecetaResponse> receta = trabajo.receta();
        if (receta == null || receta.isEmpty()) {
            log.info("[CONSUMO-STOCK] Trabajo {} no tiene receta. Pedido {} sin descuento.",
                    trabajo.nombre(), pedido.getNroPedido());
            pedido.setStockConsumido(true);
            pedido.setFechaStockConsumido(LocalDateTime.now());
            return true;
        }

        // 2. Por cada ingrediente, registrar SALIDA en stock
        String motivo = String.format("Producción pedido %s", pedido.getNroPedido());
        int ok = 0, errores = 0;
        for (IngredienteRecetaResponse ing : receta) {
            try {
                MovimientoRequest mov = new MovimientoRequest();
                mov.setMaterialId(ing.materialId());
                mov.setMaterialNombre(ing.materialNombre());
                mov.setTipo(TipoMovimiento.SALIDA);
                mov.setCantidad(ing.cantidad() != null ? ing.cantidad().doubleValue() : 0.0);
                mov.setMotivo(motivo);
                mov.setPedidoId(pedido.getId());
                stockService.registrarMovimiento(mov);
                ok++;
                log.info("[CONSUMO-STOCK] {} descontó {} {} de '{}' para pedido {}",
                        pedido.getNroPedido(),
                        formatBigDec(ing.cantidad()),
                        ing.unidad(),
                        ing.materialNombre(),
                        pedido.getNroPedido());
            } catch (Exception e) {
                errores++;
                log.warn("[CONSUMO-STOCK] Error descontando material id={} ({}) para pedido {}: {}",
                        ing.materialId(), ing.materialNombre(), pedido.getNroPedido(), e.getMessage());
            }
        }

        // 3. Marcar como consumido si al menos uno se procesó OK
        if (ok > 0) {
            pedido.setStockConsumido(true);
            pedido.setFechaStockConsumido(LocalDateTime.now());
            log.info("[CONSUMO-STOCK] Pedido {}: {} OK / {} errores", pedido.getNroPedido(), ok, errores);
        }
        return errores == 0;
    }

    private static String formatBigDec(BigDecimal b) {
        return b == null ? "?" : b.stripTrailingZeros().toPlainString();
    }
}
