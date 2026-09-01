package com.gs.monolito.pedidos.service;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * El descuento automático de stock es plata real saliendo del inventario sin
 * que nadie lo toque a mano — estos tests cubren la idempotencia (no
 * descontar dos veces), el caso "trabajo custom" (sin receta), y que un
 * ingrediente que falla no frene a los demás (ver el comentario de la clase
 * real sobre por qué el try/catch por ingrediente es intencional).
 */
@ExtendWith(MockitoExtension.class)
class ConsumoStockServiceTest {

    @Mock private ITipoTrabajoService catalogoService;
    @Mock private IStockService stockService;

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
    void siCatalogoFalla_noRompeYQuedaSinConsumirParaReintentar() {
        Pedido pedido = pedidoConTrabajo(5L);
        when(catalogoService.buscarPorId(5L)).thenThrow(new RuntimeException("catalogo caído"));

        boolean ok = consumoStockService.descontarSiCorresponde(pedido);

        assertThat(ok).isFalse();
        assertThat(pedido.isStockConsumido()).isFalse();
        verifyNoInteractions(stockService);
    }

    @Test
    void recetaVacia_marcaConsumidoSinTocarStock() {
        Pedido pedido = pedidoConTrabajo(5L);
        when(catalogoService.buscarPorId(5L)).thenReturn(trabajoConReceta(List.of()));

        boolean ok = consumoStockService.descontarSiCorresponde(pedido);

        assertThat(ok).isTrue();
        assertThat(pedido.isStockConsumido()).isTrue();
        verifyNoInteractions(stockService);
    }

    @Test
    void recetaConIngredientes_descuentaCadaUnoComoSalidaDeStock() {
        Pedido pedido = pedidoConTrabajo(5L);
        IngredienteRecetaResponse ing1 = new IngredienteRecetaResponse(1L, 100L, "Zirconia", new BigDecimal("2.5"), "gr", null);
        IngredienteRecetaResponse ing2 = new IngredienteRecetaResponse(2L, 200L, "Resina", BigDecimal.ONE, "kit", null);
        when(catalogoService.buscarPorId(5L)).thenReturn(trabajoConReceta(List.of(ing1, ing2)));

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
    void unIngredienteQueFalla_noFrenaElDescuentoDeLosDemas() {
        Pedido pedido = pedidoConTrabajo(5L);
        IngredienteRecetaResponse ok1 = new IngredienteRecetaResponse(1L, 100L, "Zirconia", BigDecimal.ONE, "gr", null);
        IngredienteRecetaResponse falla = new IngredienteRecetaResponse(2L, 200L, "Resina", BigDecimal.ONE, "kit", null);
        when(catalogoService.buscarPorId(5L)).thenReturn(trabajoConReceta(List.of(ok1, falla)));
        lenient().when(stockService.registrarMovimiento(argThat(m -> m != null && m.getMaterialId().equals(200L))))
                .thenThrow(new RuntimeException("sin stock suficiente"));

        boolean ok = consumoStockService.descontarSiCorresponde(pedido);

        assertThat(ok).isFalse(); // hubo al menos un error real, no un simple problema de red
        assertThat(pedido.isStockConsumido()).isTrue(); // pero al menos uno se procesó OK
        verify(stockService, times(2)).registrarMovimiento(any());
    }
}
