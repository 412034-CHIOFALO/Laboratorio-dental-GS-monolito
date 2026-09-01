package com.gs.monolito.pedidos.service;

import com.gs.monolito.finanzas.dto.ComprobanteRequest;
import com.gs.monolito.finanzas.service.IFinanzasService;
import com.gs.monolito.pedidos.model.Pedido;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * La entrega de un pedido genera la deuda (comprobante) en finanzas — plata
 * real que el laboratorio espera cobrar. Cubre la idempotencia (no duplicar
 * la deuda si se reintenta) y que un fallo de finanzas no bloquee la entrega
 * (best-effort documentado en la clase real).
 */
@ExtendWith(MockitoExtension.class)
class EmisionComprobanteServiceTest {

    @Mock private IFinanzasService finanzasService;

    @InjectMocks private EmisionComprobanteService emisionComprobanteService;

    private Pedido pedidoBase() {
        return Pedido.builder()
                .id(1L).nroPedido("PED-20260101-0001")
                .odontologoId(10L).odontologoNombre("Dr. Pérez")
                .trabajo("Corona").comprobanteGenerado(false)
                .build();
    }

    @Test
    void siYaTeniaComprobante_esIdempotenteYNoVuelveALlamarAFinanzas() {
        Pedido pedido = pedidoBase();
        pedido.setComprobanteGenerado(true);

        emisionComprobanteService.emitirSiCorresponde(pedido, new BigDecimal("15000"));

        verifyNoInteractions(finanzasService);
    }

    @Test
    void sinMontoAFacturar_noGeneraComprobante() {
        Pedido pedido = pedidoBase();

        emisionComprobanteService.emitirSiCorresponde(pedido, null);
        emisionComprobanteService.emitirSiCorresponde(pedido, BigDecimal.ZERO);

        verifyNoInteractions(finanzasService);
        assertThat(pedido.isComprobanteGenerado()).isFalse();
    }

    @Test
    void montoValido_emiteLaDeudaConLosDatosDelPedidoYMarcaElFlag() {
        Pedido pedido = pedidoBase();
        ArgumentCaptor<ComprobanteRequest> captor = ArgumentCaptor.forClass(ComprobanteRequest.class);

        emisionComprobanteService.emitirSiCorresponde(pedido, new BigDecimal("15000"));

        verify(finanzasService).emitir(captor.capture());
        ComprobanteRequest req = captor.getValue();
        assertThat(req.getPedidoId()).isEqualTo(1L);
        assertThat(req.getNroPedido()).isEqualTo("PED-20260101-0001");
        assertThat(req.getOdontologoId()).isEqualTo(10L);
        assertThat(req.getOdontologoNombre()).isEqualTo("Dr. Pérez");
        assertThat(req.getMonto()).isEqualByComparingTo("15000");
        assertThat(req.getFechaVencimiento()).isEqualTo(req.getFechaEmision().plusDays(30));
        assertThat(pedido.isComprobanteGenerado()).isTrue();
    }

    @Test
    void siFinanzasFalla_laEntregaNoSeBloqueaYQuedaReintentable() {
        Pedido pedido = pedidoBase();
        doThrow(new RuntimeException("finanzas caído")).when(finanzasService).emitir(any());

        emisionComprobanteService.emitirSiCorresponde(pedido, new BigDecimal("15000"));

        assertThat(pedido.isComprobanteGenerado()).isFalse();
    }
}
