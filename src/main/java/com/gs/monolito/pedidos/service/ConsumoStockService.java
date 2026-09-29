package com.gs.monolito.pedidos.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.catalogo.dto.IngredienteRecetaResponse;
import com.gs.monolito.catalogo.dto.TipoTrabajoResponse;
import com.gs.monolito.catalogo.service.ITipoTrabajoService;
import com.gs.monolito.common.security.CurrentUser;
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
import java.util.Optional;

/**
 * Orquesta el descuento automático de stock cuando un pedido entra en producción.
 *
 * Flujo:
 *   1. PedidoService.actualizarEstado lo llama en cada movimiento dentro de
 *      producción (EN_PROCESO, CONTROL, LISTO); es idempotente por el flag
 *      stockConsumido, así que un descuento pendiente se reintenta solo.
 *   2. Si el pedido tiene catalogoTrabajoId y stockConsumido = false:
 *      a. Busca la receta del trabajo en catalogo.
 *      b. Verifica que TODOS los materiales de la receta existan en stock.
 *      c. Recién ahí registra una SALIDA por cada ingrediente.
 *   3. Marca el pedido como stockConsumido = true.
 *
 * Todo o nada, y sin excepciones cruzando servicios transaccionales:
 * - Antes, si un material de la receta no existía, stock lanzaba una
 *   excepción que se atrapaba acá — pero al cruzar un servicio @Transactional
 *   ya había marcado la transacción del cambio de estado para rollback, y el
 *   pedido no se podía mover (500).
 * - Y si fallaba un ingrediente pero otros no, el pedido quedaba marcado como
 *   consumido y el que faltó no se descontaba nunca, sin aviso.
 * Ahora, si falta la receta o algún material, no se descuenta nada: el pedido
 * cambia de estado igual, queda registrado en la auditoría qué falta, y se
 * vuelve a intentar en su próximo cambio de estado.
 */
@Service
@RequiredArgsConstructor
public class ConsumoStockService {

    private static final Logger log = LoggerFactory.getLogger(ConsumoStockService.class);

    private final ITipoTrabajoService catalogoService;
    private final IStockService stockService;
    private final AuditoriaService auditoria;

    /**
     * Descuenta del stock todos los materiales de la receta del pedido.
     * Idempotente: si ya se consumió, no hace nada.
     *
     * @return true si se consumió (o no había nada que consumir); false si
     *         quedó pendiente porque falta la receta o algún material.
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

        // 1. Receta del trabajo
        Optional<TipoTrabajoResponse> trabajoOpt = catalogoService.buscarOpcional(pedido.getCatalogoTrabajoId());
        if (trabajoOpt.isEmpty()) {
            dejarPendiente(pedido, "el trabajo del catálogo (id " + pedido.getCatalogoTrabajoId() + ") ya no existe");
            return false;
        }
        TipoTrabajoResponse trabajo = trabajoOpt.get();
        List<IngredienteRecetaResponse> receta = trabajo.receta();
        if (receta == null || receta.isEmpty()) {
            log.info("[CONSUMO-STOCK] Trabajo {} no tiene receta. Pedido {} sin descuento.",
                    trabajo.nombre(), pedido.getNroPedido());
            marcarConsumido(pedido);
            return true;
        }

        // 2. Todos los materiales tienen que existir antes de tocar nada
        List<String> faltantes = receta.stream()
                .filter(ing -> !stockService.existeMaterial(ing.materialNombre(), ing.materialId()))
                .map(ing -> ing.materialNombre() != null ? ing.materialNombre() : "material id " + ing.materialId())
                .toList();
        if (!faltantes.isEmpty()) {
            dejarPendiente(pedido, "la receta de '" + trabajo.nombre() + "' usa materiales que no están en stock: "
                    + String.join(", ", faltantes));
            return false;
        }

        // 3. Una SALIDA por ingrediente
        String motivo = String.format("Producción pedido %s", pedido.getNroPedido());
        for (IngredienteRecetaResponse ing : receta) {
            MovimientoRequest mov = new MovimientoRequest();
            mov.setMaterialId(ing.materialId());
            mov.setMaterialNombre(ing.materialNombre());
            mov.setTipo(TipoMovimiento.SALIDA);
            mov.setCantidad(ing.cantidad() != null ? ing.cantidad().doubleValue() : 0.0);
            mov.setMotivo(motivo);
            mov.setPedidoId(pedido.getId());
            stockService.registrarMovimiento(mov);
            log.info("[CONSUMO-STOCK] {} descontó {} {} de '{}'",
                    pedido.getNroPedido(), formatBigDec(ing.cantidad()), ing.unidad(), ing.materialNombre());
        }
        marcarConsumido(pedido);
        return true;
    }

    private void marcarConsumido(Pedido pedido) {
        pedido.setStockConsumido(true);
        pedido.setFechaStockConsumido(LocalDateTime.now());
    }

    private void dejarPendiente(Pedido pedido, String motivo) {
        log.warn("[CONSUMO-STOCK] Pedido {}: descuento de stock pendiente — {}.", pedido.getNroPedido(), motivo);
        auditoria.registrar(CurrentUser.usernameOrSistema(), "STOCK", "Descuento de stock pendiente",
                "Pedido " + pedido.getNroPedido(),
                "No se descontó nada: " + motivo + ". Se reintenta en el próximo cambio de estado del pedido.");
    }

    private static String formatBigDec(BigDecimal b) {
        return b == null ? "?" : b.stripTrailingZeros().toPlainString();
    }
}
