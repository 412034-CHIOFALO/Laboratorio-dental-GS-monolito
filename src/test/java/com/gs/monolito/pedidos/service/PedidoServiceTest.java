package com.gs.monolito.pedidos.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.pedidos.dto.EntregaRequest;
import com.gs.monolito.pedidos.exception.BusinessException;
import com.gs.monolito.pedidos.model.EstadoPedido;
import com.gs.monolito.pedidos.model.Odontologo;
import com.gs.monolito.pedidos.model.Pedido;
import com.gs.monolito.pedidos.model.Prioridad;
import com.gs.monolito.pedidos.repository.OdontologoRepository;
import com.gs.monolito.pedidos.repository.PedidoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Cubre las dos orquestaciones con plata/stock real en juego que
 * {@link PedidoService} dispara al cambiar de estado: el descuento
 * automático de stock al entrar en producción, y la generación de la deuda
 * en finanzas al entregar — ambas "best-effort" (no deben bloquear el cambio
 * de estado del pedido si fallan), así que lo que importa acá es que SE
 * LLAMEN en el momento justo, no cómo resuelven internamente (eso lo cubren
 * ConsumoStockServiceTest y EmisionComprobanteServiceTest).
 */
@ExtendWith(MockitoExtension.class)
class PedidoServiceTest {

    @Mock private PedidoRepository pedidoRepository;
    @Mock private OdontologoRepository odontologoRepository;
    @Mock private IOdontologoService odontologoService;
    @Mock private ConsumoStockService consumoStockService;
    @Mock private NotificacionBotService notificacionBotService;
    @Mock private EmisionComprobanteService emisionComprobanteService;
    @Mock private AuditoriaService auditoria;

    private PedidoService pedidoService;

    @BeforeEach
    void setUp() {
        pedidoService = new PedidoService(pedidoRepository, odontologoRepository, odontologoService,
                consumoStockService, notificacionBotService, emisionComprobanteService, auditoria);
        ReflectionTestUtils.setField(pedidoService, "diasLimiteAtraso", 6);
        lenient().when(pedidoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private Pedido pedidoBase(EstadoPedido estado) {
        return Pedido.builder()
                .id(1L).nroPedido("PED-20260101-0001")
                .odontologoId(10L).odontologoNombre("Dr. Pérez")
                .paciente("Juan López").trabajo("Corona")
                .fechaEntrega(LocalDate.now().plusDays(5))
                .estado(estado).prioridad(Prioridad.NORMAL)
                .precioAcordado(new BigDecimal("15000"))
                .stockConsumido(false).comprobanteGenerado(false)
                .build();
    }

    @Test
    void alEntrarAProduccionPorPrimeraVez_descuentaStock() {
        Pedido pedido = pedidoBase(EstadoPedido.RECIBIDO);
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(pedido));

        pedidoService.actualizarEstado(1L, EstadoPedido.EN_PROCESO);

        verify(consumoStockService).descontarSiCorresponde(pedido);
    }

    @Test
    void saltoDirectoDeRecibidoAControl_tambienDescuentaStock() {
        // El Kanban permite arrastrar la tarjeta a cualquier columna, no solo la
        // adyacente — por eso el disparador es alcanzoProduccion(), no una
        // igualdad exacta con EN_PROCESO.
        Pedido pedido = pedidoBase(EstadoPedido.RECIBIDO);
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(pedido));

        pedidoService.actualizarEstado(1L, EstadoPedido.CONTROL);

        verify(consumoStockService).descontarSiCorresponde(pedido);
    }

    @Test
    void siYaEstabaEnProduccion_noVuelveADescontarStock() {
        Pedido pedido = pedidoBase(EstadoPedido.EN_PROCESO);
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(pedido));

        pedidoService.actualizarEstado(1L, EstadoPedido.CONTROL);

        verify(consumoStockService, never()).descontarSiCorresponde(any());
    }

    @Test
    void alPasarAListo_notificaAlOdontologoPorWhatsapp() {
        Pedido pedido = pedidoBase(EstadoPedido.CONTROL);
        Odontologo od = Odontologo.builder().id(10L).nombre("Dr. Pérez").telefono("1155443322").build();
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(pedido));
        when(odontologoRepository.findById(10L)).thenReturn(Optional.of(od));

        pedidoService.actualizarEstado(1L, EstadoPedido.LISTO);

        verify(notificacionBotService).notificarPedidoListo("PED-20260101-0001", "Corona", od);
    }

    @Test
    void marcarEntregado_siNoEstaListo_rechazaConBusinessExceptionYNoTocaFinanzas() {
        Pedido pedido = pedidoBase(EstadoPedido.EN_PROCESO);
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(pedido));

        EntregaRequest req = new EntregaRequest();
        req.setRetiradoPor("Juan López");

        assertThatThrownBy(() -> pedidoService.marcarEntregado(1L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("LISTO");

        verifyNoInteractions(emisionComprobanteService);
    }

    @Test
    void marcarEntregado_generaLaDeudaPorElPrecioAcordadoCuandoNoSeIndicaMonto() {
        Pedido pedido = pedidoBase(EstadoPedido.LISTO);
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(pedido));

        EntregaRequest req = new EntregaRequest();
        req.setRetiradoPor("Juan López");

        pedidoService.marcarEntregado(1L, req);

        assertThat(pedido.getEstado()).isEqualTo(EstadoPedido.ENTREGADO);
        verify(emisionComprobanteService).emitirSiCorresponde(pedido, new BigDecimal("15000"));
    }

    @Test
    void marcarEntregado_conMontoExplicito_facturaEseMontoEnVezDelPrecioAcordado() {
        Pedido pedido = pedidoBase(EstadoPedido.LISTO);
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(pedido));

        EntregaRequest req = new EntregaRequest();
        req.setRetiradoPor("Juan López");
        req.setMonto(new BigDecimal("20000"));

        pedidoService.marcarEntregado(1L, req);

        verify(emisionComprobanteService).emitirSiCorresponde(pedido, new BigDecimal("20000"));
    }
}
