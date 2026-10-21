package com.gs.monolito.pedidos.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.catalogo.model.TipoTrabajo;
import com.gs.monolito.catalogo.repository.TipoTrabajoRepository;
import com.gs.monolito.pedidos.config.CorreoProperties;
import com.gs.monolito.pedidos.model.Odontologo;
import com.gs.monolito.pedidos.model.Prioridad;
import com.gs.monolito.pedidos.model.SolicitudAdjunto;
import com.gs.monolito.pedidos.model.SolicitudEntrante;
import com.gs.monolito.pedidos.repository.OdontologoRepository;
import com.gs.monolito.pedidos.repository.SolicitudAdjuntoRepository;
import com.gs.monolito.pedidos.repository.SolicitudEntranteRepository;
import com.gs.monolito.pedidos.service.RecepcionSolicitudService.Adjunto;
import com.gs.monolito.pedidos.service.RecepcionSolicitudService.DatosCorreo;
import com.gs.monolito.pedidos.service.RecepcionSolicitudService.Resultado;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Qué pasa cuando llega un mail: se guarda una sola vez, se reconoce al odontólogo por su email, se adelanta el
 * formulario con lo que se pudo leer, y los adjuntos se aceptan o se rechazan explicando por qué.
 */
class RecepcionSolicitudServiceTest {

    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-21T15:00:00Z"), ZoneId.of("America/Argentina/Buenos_Aires"));
    private static final String STL = "solid t\nfacet normal 0 0 0\nouter loop\nvertex 0 0 0\nvertex 10 0 0\nvertex 0 20 0\n"
            + "endloop\nendfacet\nendsolid t\n";

    private SolicitudEntranteRepository solicitudRepo;
    private SolicitudAdjuntoRepository adjuntoRepo;
    private OdontologoRepository odontologoRepo;
    private PedidosDocumentoStorageService storage;
    private AuditoriaService auditoria;
    private CorreoProperties props;
    private RecepcionSolicitudService servicio;
    private final List<SolicitudAdjunto> adjuntosGuardados = new ArrayList<>();

    @BeforeEach
    void preparar() {
        solicitudRepo = mock(SolicitudEntranteRepository.class);
        adjuntoRepo = mock(SolicitudAdjuntoRepository.class);
        odontologoRepo = mock(OdontologoRepository.class);
        storage = mock(PedidosDocumentoStorageService.class);
        auditoria = mock(AuditoriaService.class);
        props = new CorreoProperties();
        TipoTrabajoRepository tipoRepo = mock(TipoTrabajoRepository.class);
        TipoTrabajo corona = new TipoTrabajo();
        corona.setId(2L);
        corona.setNombre("Corona de Zirconio");
        when(tipoRepo.findByActivoTrue()).thenReturn(List.of(corona));
        when(solicitudRepo.save(any(SolicitudEntrante.class))).thenAnswer(inv -> {
            SolicitudEntrante s = inv.getArgument(0);
            if (s.getId() == null) s.setId(77L);
            return s;
        });
        when(adjuntoRepo.save(any(SolicitudAdjunto.class))).thenAnswer(inv -> {
            adjuntosGuardados.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(storage.subirStream(any(InputStream.class), anyLong(), anyString(), anyString(), eq(77L), eq("solicitudes")))
                .thenAnswer(inv -> "solicitudes/77/2026/10/" + inv.getArgument(3));
        servicio = new RecepcionSolicitudService(solicitudRepo, adjuntoRepo, odontologoRepo, tipoRepo, new ExtractorSolicitud(),
                new MallaInspector(MallaInspector.MAX_TRIANGULOS_DEFECTO), storage, auditoria, props, RELOJ);
    }

    private static DatosCorreo mail(String cuerpo, Adjunto... adjuntos) {
        return new DatosCorreo("<abc@gmail.com>", "perez@clinica.com", "Dr. Pérez", "Pedido corona", cuerpo,
                LocalDateTime.of(2026, 10, 21, 11, 0), List.of(adjuntos));
    }

    private static Adjunto adjunto(String nombre, byte[] datos) {
        return new Adjunto(nombre, datos, null);
    }

    private static byte[] pdf() {
        return "%PDF-1.4 receta".getBytes(StandardCharsets.US_ASCII);
    }

    // ── El mail ──────────────────────────────────────────────────────

    @Test
    void unMailNuevoReconoceAlOdontologoYAdelantaElFormulario() {
        Odontologo perez = Odontologo.builder().id(5L).nombre("Dr. Carlos Pérez").email("perez@clinica.com").build();
        when(odontologoRepo.findByActivoTrueAndEmailIgnoreCase("perez@clinica.com")).thenReturn(List.of(perez));

        Resultado r = servicio.registrar(mail("Les mando una corona de zirconio para la paciente María González. "
                + "La necesito para el viernes 30/10, es urgente. $85.000"));

        assertThat(r.nueva()).isTrue();
        SolicitudEntrante s = r.solicitud();
        assertThat(s.getEstado()).isEqualTo(SolicitudEntrante.Estado.NUEVA);
        assertThat(s.getOdontologoId()).isEqualTo(5L);
        assertThat(s.getOdontologoNombre()).isEqualTo("Dr. Carlos Pérez");
        assertThat(s.getSugPaciente()).isEqualTo("María González");
        assertThat(s.getSugTrabajo()).isEqualTo("Corona de Zirconio");
        assertThat(s.getSugCatalogoTrabajoId()).isEqualTo(2L);
        assertThat(s.getSugFechaEntrega()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(s.getSugPrioridad()).isEqualTo(Prioridad.URGENTE);
        assertThat(s.getSugPrecio()).isEqualByComparingTo("85000");
        verify(auditoria).registrar(eq("correo"), eq("SOLICITUD"), eq("Solicitud recibida por mail"), eq("Solicitud #77"), anyString());
    }

    @Test
    void elMismoMailNoSeCargaDosVeces() {
        SolicitudEntrante yaExiste = SolicitudEntrante.builder().id(9L).messageId("<abc@gmail.com>").build();
        when(solicitudRepo.findByMessageId("<abc@gmail.com>")).thenReturn(Optional.of(yaExiste));

        Resultado r = servicio.registrar(mail("hola", adjunto("receta.pdf", pdf())));

        assertThat(r.nueva()).isFalse();
        assertThat(r.solicitud().getId()).isEqualTo(9L);
        verify(solicitudRepo, never()).save(any());
        verify(storage, never()).subirStream(any(), anyLong(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void siVariosOdontologosComparteLaCasillaNoSeAdivinaCual() {
        when(odontologoRepo.findByActivoTrueAndEmailIgnoreCase("perez@clinica.com")).thenReturn(List.of(
                Odontologo.builder().id(1L).nombre("Dr. A").build(), Odontologo.builder().id(2L).nombre("Dr. B").build()));

        SolicitudEntrante s = servicio.registrar(mail("hola")).solicitud();

        assertThat(s.getOdontologoId()).isNull();
    }

    @Test
    void unRemitenteDesconocidoQuedaSinIdentificar() {
        SolicitudEntrante s = servicio.registrar(mail("hola")).solicitud();

        assertThat(s.getOdontologoId()).isNull();
        assertThat(s.getRemitenteEmail()).isEqualTo("perez@clinica.com");
    }

    // ── Adjuntos ─────────────────────────────────────────────────────

    @Test
    void losEscaneosValidosYLosDocumentosSeGuardanConLoQueSeMidio() {
        servicio.registrar(mail("hola", adjunto("arcada.stl", STL.getBytes(StandardCharsets.US_ASCII)), adjunto("receta.pdf", pdf())));

        assertThat(adjuntosGuardados).hasSize(2);
        SolicitudAdjunto escaneo = adjuntosGuardados.get(0);
        assertThat(escaneo.getTipo()).isEqualTo(SolicitudAdjunto.Tipo.ESCANEO);
        assertThat(escaneo.getFormato()).isEqualTo("STL_ASCII");
        assertThat(escaneo.getTriangulos()).isEqualTo(1);
        assertThat(escaneo.getDimXMm()).isEqualByComparingTo("10");
        assertThat(escaneo.getSha256()).hasSize(64);
        assertThat(escaneo.getObjectKey()).startsWith("solicitudes/77/");
        SolicitudAdjunto doc = adjuntosGuardados.get(1);
        assertThat(doc.getTipo()).isEqualTo(SolicitudAdjunto.Tipo.DOCUMENTO);
        assertThat(doc.getContentType()).isEqualTo("application/pdf");
    }

    @Test
    void losAdjuntosQueNoSirvenSeOmitenYLaSolicitudDiceCuales() {
        SolicitudEntrante s = servicio.registrar(mail("hola",
                adjunto("virus.exe", "MZ....".getBytes(StandardCharsets.US_ASCII)),
                adjunto("roto.stl", STL.replace("vertex 0 20 0\n", "").getBytes(StandardCharsets.US_ASCII)),
                adjunto("falso.pdf", "esto no es un pdf".getBytes(StandardCharsets.US_ASCII)),
                adjunto("vacio.png", new byte[0]),
                new Adjunto("enorme.stl", null, "pesa 90 MB y el máximo es 30 MB"),
                adjunto("receta.pdf", pdf()))).solicitud();

        assertThat(adjuntosGuardados).extracting(SolicitudAdjunto::getFileName).containsExactly("receta.pdf");
        assertThat(s.getNotaAdjuntos())
                .contains("virus.exe: tipo de archivo no permitido")
                .contains("roto.stl: ")
                .contains("falso.pdf: el contenido no coincide")
                .contains("vacio.png: está vacío")
                .contains("enorme.stl: pesa 90 MB");
    }

    @Test
    void elNombreDelAdjuntoSeLimpiaDeRutas() {
        servicio.registrar(mail("hola", adjunto("..\\..\\windows\\receta.pdf", pdf())));

        assertThat(adjuntosGuardados).extracting(SolicitudAdjunto::getFileName).containsExactly("receta.pdf");
    }

    @Test
    void elMaximoDeAdjuntosSeRespeta() {
        props.setMaxAdjuntos(2);

        SolicitudEntrante s = servicio.registrar(mail("hola",
                adjunto("a.pdf", pdf()), adjunto("b.pdf", pdf()), adjunto("c.pdf", pdf()))).solicitud();

        assertThat(adjuntosGuardados).hasSize(2);
        assertThat(s.getNotaAdjuntos()).contains("c.pdf: se superó el máximo de 2 adjuntos");
    }

    @Test
    void siElAlmacenamientoNoEstaDisponibleSeAvisaPeroLaSolicitudSeGuarda() {
        when(storage.subirStream(any(InputStream.class), anyLong(), anyString(), anyString(), eq(77L), eq("solicitudes"))).thenReturn(null);

        SolicitudEntrante s = servicio.registrar(mail("hola", adjunto("receta.pdf", pdf()))).solicitud();

        assertThat(adjuntosGuardados).isEmpty();
        assertThat(s.getNotaAdjuntos()).contains("receta.pdf: no se pudo guardar");
        assertThat(s.getId()).isEqualTo(77L);
    }

    @Test
    void siFallaAMitadSeBorranLosArchivosYaSubidosParaNoDejarHuerfanos() {
        when(adjuntoRepo.save(any(SolicitudAdjunto.class)))
                .thenAnswer(inv -> inv.getArgument(0))
                .thenThrow(new IllegalStateException("base caída"));

        assertThatThrownBy(() -> servicio.registrar(mail("hola", adjunto("uno.pdf", pdf()), adjunto("dos.pdf", pdf()))))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<String> borrados = ArgumentCaptor.forClass(String.class);
        verify(storage, org.mockito.Mockito.atLeastOnce()).eliminar(borrados.capture());
        assertThat(borrados.getAllValues()).contains("solicitudes/77/2026/10/uno.pdf");
    }

    @Test
    void lasFirmasDeLasFotosYElPdfSeVerifican() {
        assertThat(RecepcionSolicitudService.firmaCorresponde("png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2})).isTrue();
        assertThat(RecepcionSolicitudService.firmaCorresponde("jpg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).isTrue();
        assertThat(RecepcionSolicitudService.firmaCorresponde("png", "%PDF-1".getBytes(StandardCharsets.US_ASCII))).isFalse();
        assertThat(RecepcionSolicitudService.firmaCorresponde("webp", "RIFF1234WEBPxx".getBytes(StandardCharsets.US_ASCII))).isTrue();
        assertThat(RecepcionSolicitudService.firmaCorresponde("webp", "RIFF1234WAVExx".getBytes(StandardCharsets.US_ASCII))).isFalse();
        assertThat(RecepcionSolicitudService.firmaCorresponde("pdf", new byte[2])).isFalse();
    }
}
