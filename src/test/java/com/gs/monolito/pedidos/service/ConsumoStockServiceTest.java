package com.gs.monolito.pedidos.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.catalogo.dto.IngredienteRecetaResponse;
import com.gs.monolito.catalogo.dto.TipoTrabajoResponse;
import com.gs.monolito.catalogo.service.ITipoTrabajoService;
import com.gs.monolito.pedidos.model.Pedido;
import com.gs.monolito.stock.dto.MovimientoRequest;
import com.gs.monolito.stock.model.TipoMovimiento;
import com.gs.monolito.stock.service.IStockService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * El descuento automático de stock es inventario saliendo sin que nadie lo
 * toque a mano. Cubre: idempotencia (no descontar dos veces), trabajo custom
 * (sin receta), y "todo o nada" — si falta la receta o algún material, no se
 * descuenta nada y queda pendiente, avisado en la auditoría.
 */
@ExtendWith(MockitoExtension.class)
class ConsumoStockServiceTest {

    @Mock private ITipoTrabajoService catalogoService;
    @Mock private IStockService stockService;
    @Mock private AuditoriaService auditoria;

    @InjectMocks private ConsumoStockService consumoStockService;

    private Pedido pedidoConTrabajo(Long catalogoTrabajoId) {
        return Pedido.builder()
                .id(1L).nroPedido("PED-20260101-0001")
                .catalogoTrabajoId(catalogoTrabajoId)
                .stockConsumido(false)
                .build();
    }

    private TipoTrabajoResponse trabajoConReceta(List<IngredienteRecetaResponse> receta) {
        return new TipoTrabajoResponse(5L, "Corona", null, BigDecimal.TEN, null, null, null, true, receta, null, null);
    }

    private final IngredienteRecetaResponse zirconia =
            new IngredienteRecetaResponse(1L, 100L, "Zirconia", new BigDecimal("2.5"), "gr", null);
    private final IngredienteRecetaResponse resina =
            new IngredienteRecetaResponse(2L, 200L, "Resina", BigDecimal.ONE, "kit", null);

    @Test
    void siYaEstabaConsumido_esIdempotenteYNoLlamaANada() {
        Pedido pedido = pedidoConTrabajo(5L);
        pedido.setStockConsumido(true);

        boolean ok = consumoStockService.descontarSiCorresponde(pedido);

        assertThat(ok).isTrue();
        verifyNoInteractions(catalogoService, stockService);
    }

    @Test
    void sinCatalogoTrabajoId_esTrabajoCustomYNoDescuentaNada() {
        Pedido pedido = pedidoConTrabajo(null);

        boolean ok = consumoStockService.descontarSiCorresponde(pedido);

        assertThat(ok).isTrue();
        assertThat(pedido.isStockConsumido()).isFalse();
        verifyNoInteractions(catalogoService, stockService);
    }

    @Test
    void siElTrabajoYaNoExiste_quedaPendienteSinLanzarYSeAudita() {
        Pedido pedido = pedidoConTrabajo(5L);
        when(catalogoService.buscarOpcional(5L)).thenReturn(Optional.empty());

        boolean ok = consumoStockService.descontarSiCorresponde(pedido);

        assertThat(ok).isFalse();
        assertThat(pedido.isStockConsumido()).isFalse();
        verifyNoInteractions(stockService);
        verify(auditoria).registrar(any(), eq("STOCK"), eq("Descuento de stock pendiente"), any(), anyString());
    }

    @Test
    void recetaVacia_marcaConsumidoSinTocarStock() {
        Pedido pedido = pedidoConTrabajo(5L);
        when(catalogoService.buscarOpcional(5L)).thenReturn(Optional.of(trabajoConReceta(List.of())));

        boolean ok = consumoStockService.descontarSiCorresponde(pedido);

        assertThat(ok).isTrue();
        assertThat(pedido.isStockConsumido()).isTrue();
        verifyNoInteractions(stockService);
    }

    @Test
    void recetaConIngredientes_descuentaCadaUnoComoSalidaDeStock() {
        Pedido pedido = pedidoConTrabajo(5L);
        when(catalogoService.buscarOpcional(5L)).thenReturn(Optional.of(trabajoConReceta(List.of(zirconia, resina))));
        when(stockService.existeMaterial(anyString(), anyLong())).thenReturn(true);

        boolean ok = consumoStockService.descontarSiCorresponde(pedido);

        assertThat(ok).isTrue();
        assertThat(pedido.isStockConsumido()).isTrue();

        ArgumentCaptor<MovimientoRequest> captor = ArgumentCaptor.forClass(MovimientoRequest.class);
        verify(stockService, times(2)).registrarMovimiento(captor.capture());
        List<MovimientoRequest> movs = captor.getAllValues();
        assertThat(movs).extracting(MovimientoRequest::getMaterialId).containsExactly(100L, 200L);
        assertThat(movs).allSatisfy(m -> {
            assertThat(m.getTipo()).isEqualTo(TipoMovimiento.SALIDA);
            assertThat(m.getPedidoId()).isEqualTo(1L);
            assertThat(m.getMotivo()).contains(pedido.getNroPedido());
        });
    }

    @Test
    void siFaltaUnMaterial_noDescuentaNingunoYQuedaPendienteParaReintentar() {
        // Antes: descontaba los que podía, marcaba el pedido como consumido y el
        // faltante no se descontaba nunca, sin aviso.
        Pedido pedido = pedidoConTrabajo(5L);
        when(catalogoService.buscarOpcional(5L)).thenReturn(Optional.of(trabajoConReceta(List.of(zirconia, resina))));
        when(stockService.existeMaterial("Zirconia", 100L)).thenReturn(true);
        when(stockService.existeMaterial("Resina", 200L)).thenReturn(false);

        boolean ok = consumoStockService.descontarSiCorresponde(pedido);

        assertThat(ok).isFalse();
        assertThat(pedido.isStockConsumido()).isFalse();
        verify(stockService, never()).registrarMovimiento(any());
        verify(auditoria).registrar(any(), eq("STOCK"), eq("Descuento de stock pendiente"), any(), contains("Resina"));
    }
}
