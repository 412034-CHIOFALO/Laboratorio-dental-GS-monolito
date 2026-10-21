package com.gs.monolito.pedidos.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.pedidos.dto.PedidoRequest;
import com.gs.monolito.pedidos.dto.PedidoResponse;
import com.gs.monolito.pedidos.exception.BusinessException;
import com.gs.monolito.pedidos.model.DocumentoPedido;
import com.gs.monolito.pedidos.model.EscaneosPedido;
import com.gs.monolito.pedidos.model.Odontologo;
import com.gs.monolito.pedidos.model.SolicitudAdjunto;
import com.gs.monolito.pedidos.model.SolicitudEntrante;
import com.gs.monolito.pedidos.repository.DocumentoPedidoRepository;
import com.gs.monolito.pedidos.repository.EscaneosPedidoRepository;
import com.gs.monolito.pedidos.repository.OdontologoRepository;
import com.gs.monolito.pedidos.repository.SolicitudAdjuntoRepository;
import com.gs.monolito.pedidos.repository.SolicitudEntranteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Lo que hace una persona con una solicitud: convertirla en pedido (una sola vez) o descartarla. */
class SolicitudServiceTest {

    private SolicitudEntranteRepository solicitudRepo;
    private SolicitudAdjuntoRepository adjuntoRepo;
    private EscaneosPedidoRepository escaneoRepo;
    private DocumentoPedidoRepository documentoRepo;
    private OdontologoRepository odontologoRepo;
    private IPedidoService pedidoService;
    private AuditoriaService auditoria;
    private SolicitudService servicio;
    private SolicitudEntrante solicitud;

    @BeforeEach
    void preparar() {
        solicitudRepo = mock(SolicitudEntranteRepository.class);
        adjuntoRepo = mock(SolicitudAdjuntoRepository.class);
        escaneoRepo = mock(EscaneosPedidoRepository.class);
        documentoRepo = mock(DocumentoPedidoRepository.class);
        odontologoRepo = mock(OdontologoRepository.class);
        pedidoService = mock(IPedidoService.class);
        auditoria = mock(AuditoriaService.class);
        servicio = new SolicitudService(solicitudRepo, adjuntoRepo, escaneoRepo, documentoRepo, odontologoRepo, pedidoService, auditoria,
                Clock.fixed(Instant.parse("2026-10-21T15:00:00Z"), ZoneId.of("America/Argentina/Buenos_Aires")));

        solicitud = SolicitudEntrante.builder().id(7L).estado(SolicitudEntrante.Estado.NUEVA)
                .remitenteEmail("perez@clinica.com").asunto("Pedido corona").build();
        when(solicitudRepo.findById(7L)).thenReturn(Optional.of(solicitud));
        when(solicitudRepo.save(any(SolicitudEntrante.class))).thenAnswer(inv -> inv.getArgument(0));
        when(adjuntoRepo.findBySolicitudIdOrderByIdAsc(7L)).thenReturn(List.of(
                SolicitudAdjunto.builder().id(1L).solicitudId(7L).tipo(SolicitudAdjunto.Tipo.ESCANEO).objectKey("solicitudes/7/a.stl")
                        .fileName("arcada.stl").contentType("application/octet-stream").tamanioBytes(1234L)
                        .formato("STL_BINARIO").triangulos(5000L).dimXMm(new BigDecimal("60")).sha256("a".repeat(64)).build(),
                SolicitudAdjunto.builder().id(2L).solicitudId(7L).tipo(SolicitudAdjunto.Tipo.DOCUMENTO).objectKey("solicitudes/7/r.pdf")
                        .fileName("receta.pdf").contentType("application/pdf").tamanioBytes(99L).build()));
    }

    private PedidoResponse pedidoCreado(long odontologoId) {
        PedidoResponse p = mock(PedidoResponse.class);
        when(p.id()).thenReturn(300L);
        when(p.nroPedido()).thenReturn("PED-20261021-0005");
        when(p.odontologoId()).thenReturn(odontologoId);
        when(p.odontologoNombre()).thenReturn("Dr. Pérez");
        when(pedidoService.crear(any(PedidoRequest.class))).thenReturn(p);
        return p;
    }

    private static PedidoRequest formulario() {
        PedidoRequest r = new PedidoRequest();
        r.setOdontologoNombre("Dr. Pérez");
        r.setPaciente("María González");
        r.setTrabajo("Corona");
        r.setFechaEntrega(LocalDate.of(2026, 10, 30));
        r.setObservaciones("Color A2");
        return r;
    }

    @Test
    void convertirCreaElPedidoLePasaLosAdjuntosYCierraLaSolicitud() {
        pedidoCreado(5L);
        when(odontologoRepo.findById(5L)).thenReturn(Optional.of(Odontologo.builder().id(5L).nombre("Dr. Pérez").build()));

        servicio.convertir(7L, formulario());

        ArgumentCaptor<PedidoRequest> req = ArgumentCaptor.forClass(PedidoRequest.class);
        verify(pedidoService).crear(req.capture());
        assertThat(req.getValue().getObservaciones()).isEqualTo("Color A2 | Recibido por mail de perez@clinica.com");

        ArgumentCaptor<EscaneosPedido> escaneo = ArgumentCaptor.forClass(EscaneosPedido.class);
        verify(escaneoRepo).save(escaneo.capture());
        assertThat(escaneo.getValue().getPedidoId()).isEqualTo(300L);
        assertThat(escaneo.getValue().getObjectKey()).isEqualTo("solicitudes/7/a.stl");   // el mismo objeto, no una copia
        assertThat(escaneo.getValue().getFormato()).isEqualTo("STL_BINARIO");
        assertThat(escaneo.getValue().getTriangulos()).isEqualTo(5000L);
        assertThat(escaneo.getValue().getSha256()).hasSize(64);
        assertThat(escaneo.getValue().getSubidoPor()).isEqualTo("correo");

        ArgumentCaptor<DocumentoPedido> doc = ArgumentCaptor.forClass(DocumentoPedido.class);
        verify(documentoRepo).save(doc.capture());
        assertThat(doc.getValue().getFileName()).isEqualTo("receta.pdf");
        assertThat(doc.getValue().getPedidoId()).isEqualTo(300L);

        assertThat(solicitud.getEstado()).isEqualTo(SolicitudEntrante.Estado.CONVERTIDA);
        assertThat(solicitud.getPedidoId()).isEqualTo(300L);
        assertThat(solicitud.getFechaResolucion()).isNotNull();
        verify(auditoria).registrar(anyString(), eq("SOLICITUD"), eq("Solicitud convertida en pedido"), eq("Solicitud #7"), anyString());
    }

    @Test
    void elOdontologoSinEmailAprendeElDelRemitenteParaLaProximaVez() {
        pedidoCreado(5L);
        Odontologo sinEmail = Odontologo.builder().id(5L).nombre("Dr. Pérez").build();
        when(odontologoRepo.findById(5L)).thenReturn(Optional.of(sinEmail));

        servicio.convertir(7L, formulario());

        assertThat(sinEmail.getEmail()).isEqualTo("perez@clinica.com");
        verify(odontologoRepo).save(sinEmail);
    }

    @Test
    void unOdontologoQueYaTieneEmailNoSePisa() {
        pedidoCreado(5L);
        Odontologo conEmail = Odontologo.builder().id(5L).nombre("Dr. Pérez").email("otro@clinica.com").build();
        when(odontologoRepo.findById(5L)).thenReturn(Optional.of(conEmail));

        servicio.convertir(7L, formulario());

        assertThat(conEmail.getEmail()).isEqualTo("otro@clinica.com");
        verify(odontologoRepo, never()).save(any());
    }

    @Test
    void unaSolicitudYaResueltaNoSeConvierteDeNuevo() {
        solicitud.setEstado(SolicitudEntrante.Estado.CONVERTIDA);

        assertThatThrownBy(() -> servicio.convertir(7L, formulario()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ya está convertida");

        verify(pedidoService, never()).crear(any());
    }

    @Test
    void descartarGuardaElMotivoYNoSePuedeRepetir() {
        servicio.descartar(7L, "Era una consulta de precios");

        assertThat(solicitud.getEstado()).isEqualTo(SolicitudEntrante.Estado.DESCARTADA);
        assertThat(solicitud.getMotivoDescarte()).isEqualTo("Era una consulta de precios");
        assertThatThrownBy(() -> servicio.descartar(7L, "otra vez"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ya está descartada");
    }

    @Test
    void descartarSinMotivoLoDejaAnotado() {
        servicio.descartar(7L, "  ");

        assertThat(solicitud.getMotivoDescarte()).isEqualTo("Sin motivo");
    }

    @Test
    void elDetalleIncluyeLosAdjuntosConLoMedido() {
        var d = servicio.detalle(7L);

        assertThat(d.adjuntos()).hasSize(2);
        assertThat(d.adjuntos().get(0).formato()).isEqualTo("STL_BINARIO");
        assertThat(d.identificado()).isFalse();
    }
}
