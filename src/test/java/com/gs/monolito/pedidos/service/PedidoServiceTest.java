package com.gs.monolito.pedidos.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.pedidos.dto.EntregaRequest;
import com.gs.monolito.pedidos.exception.BusinessException;
import com.gs.monolito.pedidos.model.EstadoPedido;
import com.gs.monolito.pedidos.model.Odontologo;
import com.gs.monolito.pedidos.model.Pedido;
import com.gs.monolito.pedidos.model.Prioridad;
import com.gs.monolito.pedidos.repository.DocumentoPedidoRepository;
import com.gs.monolito.pedidos.repository.EscaneosPedidoRepository;
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
    @Mock private DocumentoPedidoRepository documentoRepository;
    @Mock private EscaneosPedidoRepository escaneosRepository;
    @Mock private PedidosDocumentoStorageService documentoStorage;

    private PedidoService pedidoService;

    @BeforeEach
    void setUp() {
        pedidoService = new PedidoService(pedidoRepository, odontologoRepository, odontologoService,
                consumoStockService, notificacionBotService, emisionComprobanteService, auditoria,
                documentoRepository, escaneosRepository, documentoStorage);
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
    void dentroDeProduccion_reintentaElDescuento_queEsIdempotente() {
        // Si el descuento quedó pendiente (receta con un material que no existe),
        // se completa en el próximo movimiento. No descuenta dos veces: el flag
        // stockConsumido lo chequea ConsumoStockService (ver su test).
        Pedido pedido = pedidoBase(EstadoPedido.EN_PROCESO);
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(pedido));

        pedidoService.actualizarEstado(1L, EstadoPedido.CONTROL);

        verify(consumoStockService).descontarSiCorresponde(pedido);
    }

    @Test
    void volverARecibido_noIntentaDescontarStock() {
        Pedido pedido = pedidoBase(EstadoPedido.EN_PROCESO);
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(pedido));

        pedidoService.actualizarEstado(1L, EstadoPedido.RECIBIDO);

        verify(consumoStockService, never()).descontarSiCorresponde(any());
        assertThat(pedido.getEstado()).isEqualTo(EstadoPedido.RECIBIDO);
    }

    // ── transiciones de estado ─────────────────────────────────

    @Test
    void pasarAEntregadoPorElKanban_seRechaza_porqueNoGeneraLaDeuda() {
        Pedido pedido = pedidoBase(EstadoPedido.LISTO);
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(pedido));

        assertThatThrownBy(() -> pedidoService.actualizarEstado(1L, EstadoPedido.ENTREGADO))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Registrar entrega");
        verify(pedidoRepository, never()).save(any());
    }

    @Test
    void entregadoYCancelado_sonEstadosFinales() {
        Pedido entregado = pedidoBase(EstadoPedido.ENTREGADO);
        Pedido cancelado = pedidoBase(EstadoPedido.CANCELADO);
        cancelado.setId(2L);
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(entregado));
        when(pedidoRepository.findById(2L)).thenReturn(Optional.of(cancelado));

        assertThatThrownBy(() -> pedidoService.actualizarEstado(1L, EstadoPedido.RECIBIDO))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> pedidoService.actualizarEstado(1L, EstadoPedido.CANCELADO))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> pedidoService.actualizarEstado(2L, EstadoPedido.EN_PROCESO))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void cancelarDesdeCualquierColumnaDelKanban_estaPermitido() {
        Pedido pedido = pedidoBase(EstadoPedido.CONTROL);
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(pedido));

        pedidoService.actualizarEstado(1L, EstadoPedido.CANCELADO);

        assertThat(pedido.getEstado()).isEqualTo(EstadoPedido.CANCELADO);
    }

    // ── eliminar ───────────────────────────────────────────────

    @Test
    void eliminarUnPedidoConDeuda_oConStockDescontado_seRechaza() {
        Pedido conDeuda = pedidoBase(EstadoPedido.ENTREGADO);
        conDeuda.setComprobanteGenerado(true);
        Pedido conStock = pedidoBase(EstadoPedido.EN_PROCESO);
        conStock.setId(2L);
        conStock.setStockConsumido(true);
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(conDeuda));
        when(pedidoRepository.findById(2L)).thenReturn(Optional.of(conStock));

        assertThatThrownBy(() -> pedidoService.eliminar(1L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> pedidoService.eliminar(2L)).isInstanceOf(BusinessException.class);
        verify(pedidoRepository, never()).delete(any());
    }

    @Test
    void eliminarUnPedidoSinMovimientos_borraSusArchivosYQuedaAuditado() {
        Pedido pedido = pedidoBase(EstadoPedido.RECIBIDO);
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(pedido));
        com.gs.monolito.pedidos.model.DocumentoPedido doc = com.gs.monolito.pedidos.model.DocumentoPedido.builder()
                .id(7L).pedidoId(1L).objectKey("pedidos/1/orden.pdf").build();
        when(documentoRepository.findByPedidoIdOrderByFechaSubidaDesc(1L)).thenReturn(java.util.List.of(doc));
        when(escaneosRepository.findByPedidoIdOrderByFechaSubidaDesc(1L)).thenReturn(java.util.List.of());

        pedidoService.eliminar(1L);

        verify(documentoRepository).delete(doc);
        verify(pedidoRepository).delete(pedido);
        verify(documentoStorage).eliminar("pedidos/1/orden.pdf");
        verify(auditoria).registrar(any(), eq("ELIMINAR"), any(), any(), any());
    }

    // ── editar: la deuda sigue al odontólogo ───────────────────

    @Test
    void cambiarElOdontologoDeUnPedidoConDeudaYaPagada_seRechaza() {
        Pedido pedido = pedidoBase(EstadoPedido.ENTREGADO);
        pedido.setComprobanteGenerado(true);
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(pedido));
        when(odontologoService.buscarPorId(20L)).thenReturn(
                new com.gs.monolito.pedidos.dto.OdontologoResponse(20L, "Dra. Gómez", null, null, null, null, null, null,
                        null, true, null, null, null, false));
        when(emisionComprobanteService.reasignarOdontologoSiCorresponde(pedido, 20L, "Dra. Gómez")).thenReturn(false);
        com.gs.monolito.pedidos.dto.PedidoRequest req = new com.gs.monolito.pedidos.dto.PedidoRequest();
        req.setOdontologoId(20L);
        req.setPrecioAcordado(new BigDecimal("15000"));

        assertThatThrownBy(() -> pedidoService.actualizar(1L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("pagos");
        assertThat(pedido.getOdontologoId()).isEqualTo(10L);
    }

    @Test
    void alPasarAListo_notificaAlOdontologoPorWhatsapp() {
        Pedido pedido = pedidoBase(EstadoPedido.CONTROL);
        Odontologo od = Odontologo.builder().id(10L).nombre("Dr. Pérez").telefono("1155443322").build();
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(pedido));
        when(odontologoRepository.findById(10L)).thenReturn(Optional.of(od));

        pedidoService.actualizarEstado(1L, EstadoPedido.LISTO);

        verify(notificacionBotService).notificarPedidoListo(1L, "PED-20260101-0001", "Corona", od);
    }

    @Test
    void siYaSeAvisoQueEstabaListo_noSeRepiteElWhatsapp() {
        Pedido pedido = pedidoBase(EstadoPedido.CONTROL);
        pedido.setNotificadoListoEn(java.time.LocalDateTime.of(2026, 1, 1, 10, 0));   // LISTO -> CONTROL -> LISTO
        when(pedidoRepository.findById(1L)).thenReturn(Optional.of(pedido));

        pedidoService.actualizarEstado(1L, EstadoPedido.LISTO);

        verify(notificacionBotService, never()).notificarPedidoListo(any(), any(), any(), any());
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
