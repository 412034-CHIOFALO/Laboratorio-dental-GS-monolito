package com.gs.monolito.pedidos.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.pedidos.dto.AceptarRemitenteRequest;
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
    private IOdontologoService odontologos;
    private IPedidoService pedidoService;
    private RemitenteService remitentes;
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
        remitentes = mock(RemitenteService.class);
        odontologos = mock(IOdontologoService.class);
        servicio = new SolicitudService(solicitudRepo, adjuntoRepo, escaneoRepo, documentoRepo, odontologoRepo, odontologos, pedidoService, remitentes, auditoria,
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
    void convertirNuncaCambiaElEmailDeLaFichaDelOdontologo() {
        pedidoCreado(5L);
        Odontologo sinEmail = Odontologo.builder().id(5L).nombre("Dr. Pérez").build();
        when(odontologoRepo.findById(5L)).thenReturn(Optional.of(sinEmail));

        servicio.convertir(7L, formulario());

        assertThat(sinEmail.getEmail()).as("autorizar una dirección es decisión de una persona, no un efecto de convertir").isNull();
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

    // ── Remitentes sin autorizar ─────────────────────────────────────

    private SolicitudEntrante sinAutorizar(long id, String email, Boolean verificado) {
        SolicitudEntrante s = SolicitudEntrante.builder().id(id).estado(SolicitudEntrante.Estado.SIN_AUTORIZAR)
                .remitenteEmail(email).motivoCuarentena("Remitente desconocido").remitenteVerificado(verificado).build();
        when(solicitudRepo.findById(id)).thenReturn(Optional.of(s));
        return s;
    }

    private static Odontologo perez() {
        return Odontologo.builder().id(5L).nombre("Dr. Carlos Pérez").activo(true).build();
    }

    @Test
    void aceptarUnMailSinAutorizarLoPasaAPorRevisarConLaIdentidadQueElijioLaPersona() {
        SolicitudEntrante s = sinAutorizar(20L, "laura@consultorio.com", true);
        when(odontologoRepo.findById(5L)).thenReturn(Optional.of(perez()));

        servicio.aceptar(20L, new AceptarRemitenteRequest(SolicitudEntrante.RemitenteTipo.SECRETARIA, 5L, null, true, null));

        assertThat(s.getEstado()).isEqualTo(SolicitudEntrante.Estado.NUEVA);
        assertThat(s.getRemitenteTipo()).isEqualTo(SolicitudEntrante.RemitenteTipo.SECRETARIA);
        assertThat(s.getRemitenteEtiqueta()).isEqualTo("Secretaria de Dr. Carlos Pérez");
        assertThat(s.getOdontologoId()).isEqualTo(5L);
        assertThat(s.getMotivoCuarentena()).isNull();
        verify(remitentes).autorizar("laura@consultorio.com", SolicitudEntrante.RemitenteTipo.SECRETARIA, 5L, null);
        verify(auditoria).registrar(anyString(), eq("SOLICITUD"), eq("Mail sin autorizar aceptado"), eq("Solicitud #20"), anyString());
    }

    @Test
    void alRecordarLaDireccionPasanTambienLosDemasMailsDeEseRemitenteMenosLosQueFallaronLaVerificacion() {
        SolicitudEntrante esta = sinAutorizar(20L, "laura@consultorio.com", true);
        SolicitudEntrante otraBuena = SolicitudEntrante.builder().id(21L).estado(SolicitudEntrante.Estado.SIN_AUTORIZAR)
                .remitenteEmail("laura@consultorio.com").remitenteVerificado(true).build();
        SolicitudEntrante otraFalsa = SolicitudEntrante.builder().id(22L).estado(SolicitudEntrante.Estado.SIN_AUTORIZAR)
                .remitenteEmail("laura@consultorio.com").remitenteVerificado(false).build();
        when(solicitudRepo.findByRemitenteEmailAndEstado("laura@consultorio.com", SolicitudEntrante.Estado.SIN_AUTORIZAR))
                .thenReturn(List.of(esta, otraBuena, otraFalsa));
        when(odontologoRepo.findById(5L)).thenReturn(Optional.of(perez()));

        servicio.aceptar(20L, new AceptarRemitenteRequest(SolicitudEntrante.RemitenteTipo.ODONTOLOGO, 5L, null, true, null));

        assertThat(otraBuena.getEstado()).isEqualTo(SolicitudEntrante.Estado.NUEVA);
        assertThat(otraFalsa.getEstado()).as("un mail que no pasó la verificación se resuelve a mano").isEqualTo(SolicitudEntrante.Estado.SIN_AUTORIZAR);
    }

    @Test
    void aceptarSoloEstaVezNoRecuerdaLaDireccion() {
        SolicitudEntrante s = sinAutorizar(20L, "laura@consultorio.com", true);
        when(odontologoRepo.findById(5L)).thenReturn(Optional.of(perez()));

        servicio.aceptar(20L, new AceptarRemitenteRequest(SolicitudEntrante.RemitenteTipo.ODONTOLOGO, 5L, null, false, null));

        assertThat(s.getEstado()).isEqualTo(SolicitudEntrante.Estado.NUEVA);
        verify(remitentes, never()).autorizar(any(), any(), any(), any());
    }

    @Test
    void aceptarComoOdontologoOSecretariaExigeElegirElOdontologo() {
        sinAutorizar(20L, "laura@consultorio.com", true);

        assertThatThrownBy(() -> servicio.aceptar(20L, new AceptarRemitenteRequest(SolicitudEntrante.RemitenteTipo.SECRETARIA, null, null, true, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("odontólogo");
        verify(remitentes, never()).autorizar(any(), any(), any(), any());
    }

    @Test
    void aceptarComoOtroNoNecesitaOdontologo() {
        SolicitudEntrante s = sinAutorizar(20L, "contador@estudio.com", true);

        servicio.aceptar(20L, new AceptarRemitenteRequest(SolicitudEntrante.RemitenteTipo.OTRO, null, "Estudio contable", true, null));

        assertThat(s.getEstado()).isEqualTo(SolicitudEntrante.Estado.NUEVA);
        assertThat(s.getOdontologoId()).isNull();
        assertThat(s.getRemitenteEtiqueta()).isEqualTo("Estudio contable");
    }

    @Test
    void soloSePuedeAceptarUnMailQueEstaSinAutorizar() {
        assertThatThrownBy(() -> servicio.aceptar(7L, new AceptarRemitenteRequest(SolicitudEntrante.RemitenteTipo.OTRO, null, null, true, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ya está nueva");
    }

    @Test
    void bloquearRemitenteDelegaEnElServicioDeRemitentes() {
        sinAutorizar(20L, "spam@raro.com", false);

        servicio.bloquearRemitente(20L);

        verify(remitentes).bloquear("spam@raro.com");
    }

    @Test
    void unMailSinAutorizarSePuedeDescartarSoloEse() {
        SolicitudEntrante s = sinAutorizar(20L, "spam@raro.com", false);

        servicio.descartar(20L, "Es spam");

        assertThat(s.getEstado()).isEqualTo(SolicitudEntrante.Estado.DESCARTADA);
        verify(remitentes, never()).bloquear(any());
    }

    @Test
    void unMailSinAutorizarNoSeConvierteEnPedidoSinAceptarloAntes() {
        sinAutorizar(20L, "spam@raro.com", false);

        assertThatThrownBy(() -> servicio.convertir(20L, formulario()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("sin autorizar");
        verify(pedidoService, never()).crear(any());
    }

    @Test
    void lasCantidadesSeparanLasNuevasDeLasSinAutorizar() {
        when(solicitudRepo.countByEstado(SolicitudEntrante.Estado.NUEVA)).thenReturn(2L);
        when(solicitudRepo.countByEstado(SolicitudEntrante.Estado.SIN_AUTORIZAR)).thenReturn(5L);

        assertThat(servicio.cantidadNuevas()).isEqualTo(2);
        assertThat(servicio.cantidadSinAutorizar()).isEqualTo(5);
    }

    // ── Odontólogo que todavía no está cargado ───────────────────────

    private void creaAlOdontologo(long id, String nombre, String email) {
        when(odontologos.crear(any(com.gs.monolito.pedidos.dto.OdontologoRequest.class)))
                .thenAnswer(inv -> {
                    var r = (com.gs.monolito.pedidos.dto.OdontologoRequest) inv.getArgument(0);
                    odontologoNuevo = Odontologo.builder().id(id).nombre(r.getNombre()).email(r.getEmail()).clinica(r.getClinica()).activo(true).build();
                    return mock(com.gs.monolito.pedidos.dto.OdontologoResponse.class, inv2 -> inv2.getMethod().getName().equals("id") ? (Object) id : null);
                });
        when(odontologoRepo.findById(id)).thenAnswer(inv -> Optional.of(odontologoNuevo));
    }

    private Odontologo odontologoNuevo;

    @Test
    void aceptarComoOdontologoNuevoLoDaDeAltaConElMailDeQuienEscribeYNoNecesitaOtraRegla() {
        SolicitudEntrante s = sinAutorizar(30L, "Nuevo.Doc@clinica.com", true);
        creaAlOdontologo(77L, "Dr. Nuevo", "nuevo.doc@clinica.com");

        servicio.aceptar(30L, new AceptarRemitenteRequest(SolicitudEntrante.RemitenteTipo.ODONTOLOGO, null, null, true,
                new AceptarRemitenteRequest.NuevoOdontologo("  Dr. Nuevo ", "Clínica Sur", "")));

        ArgumentCaptor<com.gs.monolito.pedidos.dto.OdontologoRequest> ficha = ArgumentCaptor.forClass(com.gs.monolito.pedidos.dto.OdontologoRequest.class);
        verify(odontologos).crear(ficha.capture());
        assertThat(ficha.getValue().getNombre()).isEqualTo("Dr. Nuevo");
        assertThat(ficha.getValue().getEmail()).as("la ficha lleva el mail con el que escribió").isEqualTo("nuevo.doc@clinica.com");
        assertThat(ficha.getValue().getClinica()).isEqualTo("Clínica Sur");
        assertThat(ficha.getValue().getTelefono()).isNull();
        assertThat(s.getEstado()).isEqualTo(SolicitudEntrante.Estado.NUEVA);
        assertThat(s.getOdontologoId()).isEqualTo(77L);
        verify(remitentes, never()).autorizar(any(), any(), any(), any());
    }

    @Test
    void aceptarComoSecretariaDeUnOdontologoNuevoNoLeCargaElMailDeLaSecretariaALaFicha() {
        SolicitudEntrante s = sinAutorizar(31L, "laura@consultorio.com", true);
        creaAlOdontologo(78L, "Dra. Nueva", null);

        servicio.aceptar(31L, new AceptarRemitenteRequest(SolicitudEntrante.RemitenteTipo.SECRETARIA, null, null, true,
                new AceptarRemitenteRequest.NuevoOdontologo("Dra. Nueva", null, null)));

        ArgumentCaptor<com.gs.monolito.pedidos.dto.OdontologoRequest> ficha = ArgumentCaptor.forClass(com.gs.monolito.pedidos.dto.OdontologoRequest.class);
        verify(odontologos).crear(ficha.capture());
        assertThat(ficha.getValue().getEmail()).isNull();
        verify(remitentes).autorizar("laura@consultorio.com", SolicitudEntrante.RemitenteTipo.SECRETARIA, 78L, null);
        assertThat(s.getRemitenteEtiqueta()).isEqualTo("Secretaria de Dra. Nueva");
    }

    @Test
    void unOdontologoNuevoSoloSeCargaComoOdontologoOSecretaria() {
        sinAutorizar(32L, "contador@estudio.com", true);

        assertThatThrownBy(() -> servicio.aceptar(32L, new AceptarRemitenteRequest(SolicitudEntrante.RemitenteTipo.OTRO, null, null, true,
                new AceptarRemitenteRequest.NuevoOdontologo("Dr. X", null, null))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("odontólogo nuevo");
        verify(odontologos, never()).crear(any());
    }

    @Test
    void siElNombreYaExisteNoSeAceptaNiSeAutorizaNada() {
        SolicitudEntrante s = sinAutorizar(33L, "otro@clinica.com", true);
        when(odontologos.crear(any(com.gs.monolito.pedidos.dto.OdontologoRequest.class)))
                .thenThrow(new com.gs.monolito.pedidos.exception.ConflictException("Ya existe un odontólogo con nombre: Dr. Pérez"));

        assertThatThrownBy(() -> servicio.aceptar(33L, new AceptarRemitenteRequest(SolicitudEntrante.RemitenteTipo.ODONTOLOGO, null, null, true,
                new AceptarRemitenteRequest.NuevoOdontologo("Dr. Pérez", null, null))))
                .isInstanceOf(com.gs.monolito.pedidos.exception.ConflictException.class);
        assertThat(s.getEstado()).isEqualTo(SolicitudEntrante.Estado.SIN_AUTORIZAR);
        verify(remitentes, never()).autorizar(any(), any(), any(), any());
    }
}
