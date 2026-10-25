package com.gs.monolito.pedidos.service;

import com.gs.monolito.pedidos.config.CorreoProperties;
import com.gs.monolito.pedidos.model.SolicitudEntrante;
import com.gs.monolito.pedidos.service.RecepcionSolicitudService.DatosCorreo;
import com.gs.monolito.pedidos.service.RecepcionSolicitudService.Resultado;
import com.gs.monolito.pedidos.service.RemitenteService.Clasificacion;
import com.gs.monolito.pedidos.service.RemitenteService.Destino;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.store.StoredMessage;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Flags;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El lector de la casilla contra un servidor IMAP de mentira (GreenMail). Lo que más importa: un mail se marca como
 * leído SOLO cuando su solicitud quedó guardada, y un mail que siempre falla no traba a los demás.
 */
class CorreoLectorTest {

    @RegisterExtension
    GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.IMAP)
            .withConfiguration(com.icegreen.greenmail.configuration.GreenMailConfiguration.aConfig().withUser("pedidos@lab.test", "pedidos", "clave"));

    private RecepcionSolicitudService recepcion;
    private RemitenteService remitentes;
    private ConfirmacionCorreo confirmacion;
    private EstadoCorreo estado;
    private CorreoProperties props;
    private CorreoLector lector;
    private final Session sesion = Session.getInstance(new Properties());

    @BeforeEach
    void preparar() {
        recepcion = mock(RecepcionSolicitudService.class);
        remitentes = mock(RemitenteService.class);
        when(remitentes.clasificar(any(), any())).thenReturn(
                Clasificacion.conocido(SolicitudEntrante.RemitenteTipo.ODONTOLOGO, 5L, "Dr. Pérez", "Dr. Pérez", true));
        confirmacion = mock(ConfirmacionCorreo.class);
        estado = new EstadoCorreo();
        props = new CorreoProperties();
        props.setHabilitado(true);
        props.setHost("127.0.0.1");
        props.setPuerto(ServerSetupTest.IMAP.getPort());
        props.setSsl(false);
        props.setUsuario("pedidos");
        props.setClave("clave");
        props.setMaxFallos(2);
        props.setMaxAdjuntoMb(1);
        lector = new CorreoLector(props, recepcion, remitentes, confirmacion, estado);
        when(recepcion.registrar(any(DatosCorreo.class), any(Clasificacion.class)))
                .thenAnswer(inv -> new Resultado(SolicitudEntrante.builder().id(1L).build(), true));
    }

    // ── Armado de mails ──────────────────────────────────────────────

    private MimeMessage mail(String de, String asunto, String texto) throws Exception {
        MimeMessage m = new MimeMessage(sesion);
        m.setFrom(new InternetAddress(de, "Dr. Pérez", "UTF-8"));
        m.setRecipients(Message.RecipientType.TO, "pedidos@lab.test");
        m.setSubject(asunto, "UTF-8");
        m.setText(texto, "UTF-8");
        m.saveChanges();
        return m;
    }

    private MimeMessage conAdjunto(String nombre, byte[] datos) throws Exception {
        MimeMessage m = new MimeMessage(sesion);
        m.setFrom(new InternetAddress("perez@clinica.com", "Dr. Pérez", "UTF-8"));
        m.setRecipients(Message.RecipientType.TO, "pedidos@lab.test");
        m.setSubject("Con adjunto");
        MimeMultipart mp = new MimeMultipart();
        MimeBodyPart cuerpo = new MimeBodyPart();
        cuerpo.setText("Les mando el escaneo", "UTF-8");
        mp.addBodyPart(cuerpo);
        MimeBodyPart adj = new MimeBodyPart();
        adj.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(datos, "application/octet-stream")));
        adj.setFileName(MimeUtility.encodeText(nombre, "UTF-8", "B"));
        adj.setDisposition("attachment");
        mp.addBodyPart(adj);
        m.setContent(mp);
        m.saveChanges();
        return m;
    }

    private void entregar(MimeMessage m) throws Exception {
        GreenMailUser u = greenMail.getUserManager().getUserByEmail("pedidos@lab.test");
        u.deliver(m);
    }

    private List<StoredMessage> bandeja() throws Exception {
        GreenMailUser u = greenMail.getUserManager().getUserByEmail("pedidos@lab.test");
        return greenMail.getManagers().getImapHostManager().getInbox(u).getMessages();
    }

    private boolean leido(int i) throws Exception {
        return bandeja().get(i).isSet(Flags.Flag.SEEN);
    }

    private DatosCorreo registrado() {
        ArgumentCaptor<DatosCorreo> c = ArgumentCaptor.forClass(DatosCorreo.class);
        verify(recepcion).registrar(c.capture(), any());
        return c.getValue();
    }

    // ── Lo normal ────────────────────────────────────────────────────

    @Test
    void unMailNuevoSeLeeCompletoYSeMarcaLeidoRecienDespuesDeGuardarlo() throws Exception {
        entregar(mail("Perez@Clinica.com", "Pedido corona", "Hola, una corona para la paciente Gómez.\nSaludos"));
        assertThat(leido(0)).isFalse();

        assertThat(lector.leerNuevos()).isEqualTo(1);

        DatosCorreo d = registrado();
        assertThat(d.remitenteEmail()).isEqualTo("perez@clinica.com");   // en minúsculas
        assertThat(d.remitenteNombre()).isEqualTo("Dr. Pérez");
        assertThat(d.asunto()).isEqualTo("Pedido corona");
        assertThat(d.cuerpo()).contains("una corona para la paciente Gómez");
        assertThat(d.messageId()).isNotBlank();
        assertThat(leido(0)).isTrue();
        verify(confirmacion).enviar(any());
    }

    @Test
    void unMailConAdjuntoLoTraeConSuNombreYSusBytes() throws Exception {
        byte[] stl = new byte[]{1, 2, 3, (byte) 200, (byte) 255, 0, 10, 13, 42, 7};   // binario: viaja en base64, sin que el transporte lo toque
        entregar(conAdjunto("Arcada superior.stl", stl));

        lector.leerNuevos();

        DatosCorreo d = registrado();
        assertThat(d.adjuntos()).hasSize(1);
        assertThat(d.adjuntos().get(0).nombre()).isEqualTo("Arcada superior.stl");
        assertThat(d.adjuntos().get(0).datos()).isEqualTo(stl);
        assertThat(d.cuerpo()).isEqualTo("Les mando el escaneo");
    }

    @Test
    void unAdjuntoMasPesadoQueElTopeSeOmiteSinBajarloEntero() throws Exception {
        entregar(conAdjunto("enorme.stl", new byte[2 * 1024 * 1024]));   // tope: 1 MB

        lector.leerNuevos();

        var adj = registrado().adjuntos().get(0);
        assertThat(adj.datos()).isNull();
        assertThat(adj.omitidoPor()).contains("1 MB");
    }

    @Test
    void unMailSoloEnHtmlSeConvierteATexto() throws Exception {
        MimeMessage m = new MimeMessage(sesion);
        m.setFrom(new InternetAddress("perez@clinica.com"));
        m.setRecipients(Message.RecipientType.TO, "pedidos@lab.test");
        m.setSubject("Html");
        m.setContent("<html><body><p>Corona para <b>Gómez</b></p><p>Urgente&nbsp;&amp; rápido</p></body></html>", "text/html; charset=UTF-8");
        m.saveChanges();
        entregar(m);

        lector.leerNuevos();

        assertThat(registrado().cuerpo()).isEqualTo("Corona para Gómez\nUrgente & rápido");
    }

    // ── Lo que se ignora ─────────────────────────────────────────────

    @Test
    void lasNotificacionesAutomaticasSeIgnoranSinGuardarNada() throws Exception {
        entregar(mail("no-reply@servicio.com", "Tu factura", "automático"));
        MimeMessage respuestaAuto = mail("perez@clinica.com", "Fuera de oficina", "estoy de vacaciones");
        respuestaAuto.setHeader("Auto-Submitted", "auto-replied");
        entregar(respuestaAuto);
        MimeMessage lista = mail("novedades@proveedor.com", "Oferta", "comprá ya");
        lista.setHeader("List-Unsubscribe", "<mailto:baja@proveedor.com>");
        entregar(lista);

        assertThat(lector.leerNuevos()).isZero();

        verify(recepcion, never()).registrar(any(), any());
        assertThat(bandeja()).hasSize(3).allSatisfy(m -> assertThat(m.isSet(Flags.Flag.SEEN)).isTrue());
        assertThat(estado.vista().ignoradas()).isEqualTo(3);
    }

    // ── Según quién lo manda ─────────────────────────────────────────

    @Test
    void elMailDeUnRemitenteDesconocidoSeGuardaSinBajarSusAdjuntos() throws Exception {
        when(remitentes.clasificar(any(), any())).thenReturn(Clasificacion.sinAutorizar("Remitente desconocido", null));
        when(recepcion.registrar(any(DatosCorreo.class), any(Clasificacion.class))).thenAnswer(inv ->
                new Resultado(SolicitudEntrante.builder().id(3L).estado(SolicitudEntrante.Estado.SIN_AUTORIZAR).build(), true));
        entregar(conAdjunto("factura.pdf", new byte[]{'%', 'P', 'D', 'F', '-', 1}));

        assertThat(lector.leerNuevos()).as("no cuenta como solicitud nueva").isZero();

        DatosCorreo d = registrado();
        assertThat(d.cuerpo()).isEqualTo("Les mando el escaneo");
        assertThat(d.adjuntos()).hasSize(1);
        assertThat(d.adjuntos().get(0).nombre()).isEqualTo("factura.pdf");
        assertThat(d.adjuntos().get(0).datos()).as("el contenido de un desconocido no se baja").isNull();
        assertThat(d.adjuntos().get(0).omitidoPor()).contains("no está autorizado");
        assertThat(leido(0)).isTrue();
        assertThat(estado.vista().sinAutorizar()).isEqualTo(1);
        verify(confirmacion, never()).enviar(any());   // a un desconocido no se le contesta
    }

    @Test
    void elMailDeUnRemitenteBloqueadoSeIgnoraSinLeerloNiGuardarlo() throws Exception {
        when(remitentes.clasificar(any(), any())).thenReturn(Clasificacion.ignorar("Remitente bloqueado"));
        entregar(mail("spam@raro.com", "Ganaste", "hacé clic"));

        assertThat(lector.leerNuevos()).isZero();

        verify(recepcion, never()).registrar(any(), any());
        assertThat(leido(0)).isTrue();
        assertThat(estado.vista().ignoradas()).isEqualTo(1);
    }

    @Test
    void alClasificarSeLeLlevaElResultadoDeLaVerificacionDelServidor() throws Exception {
        MimeMessage m = mail("perez@clinica.com", "Pedido", "hola");
        m.setHeader("Authentication-Results", "mx.google.com; dkim=pass header.i=@clinica.com; dmarc=pass header.from=clinica.com");
        entregar(m);

        lector.leerNuevos();

        verify(remitentes).clasificar("perez@clinica.com", true);
    }

    @Test
    void unaCabeceraDeVerificacionFalsificadaPorElRemitenteNoEngania() throws Exception {
        MimeMessage m = mail("perez@clinica.com", "Pedido", "hola");
        m.setHeader("Authentication-Results", "otro.servidor.com; dmarc=pass header.from=clinica.com");
        entregar(m);

        lector.leerNuevos();

        verify(remitentes).clasificar("perez@clinica.com", null);   // no es la cabecera de nuestro servidor
    }

    @Test
    void unMailYaLeidoNoSeVuelveAProcesar() throws Exception {
        entregar(mail("perez@clinica.com", "Uno", "hola"));
        lector.leerNuevos();
        lector.leerNuevos();

        verify(recepcion, times(1)).registrar(any(), any());
    }

    // ── Cuando algo falla ────────────────────────────────────────────

    @Test
    void siFallaElGuardadoElMailSigueSinLeerParaReintentarlo() throws Exception {
        when(recepcion.registrar(any(DatosCorreo.class), any(Clasificacion.class))).thenThrow(new IllegalStateException("base caída"));
        entregar(mail("perez@clinica.com", "Pedido", "hola"));

        assertThat(lector.leerNuevos()).isZero();

        assertThat(leido(0)).as("leer el cuerpo no debe marcarlo como leído").isFalse();
    }

    @Test
    void unMailQueSiempreFallaNoTrabaALosDemasYSeDaPorLeidoAlLlegarAlMaximo() throws Exception {
        when(recepcion.registrar(any(DatosCorreo.class), any(Clasificacion.class)))
                .thenAnswer(inv -> {
                    DatosCorreo d = inv.getArgument(0);
                    if (d.asunto().equals("Venenoso")) throw new IllegalStateException("siempre falla");
                    return new Resultado(SolicitudEntrante.builder().id(2L).build(), true);
                });
        entregar(mail("perez@clinica.com", "Venenoso", "x"));
        entregar(mail("perez@clinica.com", "Normal", "y"));

        assertThat(lector.leerNuevos()).isEqualTo(1);   // el normal entra aunque el otro falle
        assertThat(leido(1)).isTrue();
        assertThat(leido(0)).isFalse();                 // primer intento fallido: se reintenta

        lector.leerNuevos();                            // segundo intento fallido = maxFallos
        assertThat(leido(0)).isTrue();
        assertThat(estado.vista().fallidas()).isEqualTo(1);
    }

    // ── sondear(): el ciclo completo ─────────────────────────────────

    @Test
    void unCicloExitosoQuedaEnElEstado() throws Exception {
        entregar(mail("perez@clinica.com", "Pedido", "hola"));

        lector.sondear();

        var v = estado.vista();
        assertThat(v.habilitado()).isFalse();   // se habilita en el arranque de Spring (PostConstruct), no acá
        assertThat(v.ultimoExito()).isNotNull();
        assertThat(v.ultimoError()).isNull();
        assertThat(v.recibidas()).isEqualTo(1);
    }

    @Test
    void siLaCasillaNoResponderNoLanzaYElErrorSeVeEnElEstado() {
        props.setPuerto(1);   // nadie escucha

        lector.sondear();

        assertThat(estado.vista().ultimoError()).isNotBlank();
        assertThat(estado.vista().ultimoExito()).isNull();
    }

    @Test
    void sinCredencialesAvisaQueFaltanEnVezDeIntentarConectar() {
        props.setHost("");

        lector.sondear();

        assertThat(estado.vista().ultimoError()).contains("GS_CORREO_HOST");
    }

    @Test
    void elHtmlATextoQuitaScriptsYEtiquetas() {
        assertThat(CorreoLector.htmlATexto("<style>p{color:red}</style><p>Hola</p><script>alert(1)</script><br>Chau")).isEqualTo("Hola\n\nChau");
        assertThat(CorreoLector.htmlATexto(null)).isEmpty();
    }

    @SuppressWarnings("unused")
    private static String sinUsar() {
        return GreenMailUtil.random();
    }
}
