package com.gs.monolito.pedidos.service;

import com.gs.monolito.pedidos.config.CorreoProperties;
import com.gs.monolito.pedidos.model.SolicitudEntrante;
import com.gs.monolito.pedidos.service.RecepcionSolicitudService.Adjunto;
import com.gs.monolito.pedidos.service.RecepcionSolicitudService.DatosCorreo;
import com.gs.monolito.pedidos.service.RecepcionSolicitudService.Resultado;
import com.gs.monolito.pedidos.service.RemitenteService.Clasificacion;
import com.gs.monolito.pedidos.service.RemitenteService.Destino;
import jakarta.annotation.PostConstruct;
import jakarta.mail.Address;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.search.FlagTerm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Lee la casilla de pedidos por IMAP y convierte cada mail nuevo en una solicitud ({@link RecepcionSolicitudService}).
 * Solo existe si {@code gs.correo.habilitado=true}.
 *
 * <p>Cuidados que el scraper anterior no tenía:</p>
 * <ul>
 *   <li>La lectura no marca el mail como leído ({@code peek}): se marca recién cuando la solicitud quedó guardada. Si
 *       algo falla, el mail sigue sin leer y se reintenta en el ciclo siguiente.</li>
 *   <li>El mismo mail nunca duplica: la solicitud es única por Message-ID, así que si falla el marcado como leído y se
 *       lo vuelve a leer, no se crea nada de nuevo.</li>
 *   <li>Un mail que falla siempre no traba a los demás: tras {@code maxFallos} intentos se lo da por leído y queda
 *       anotado como fallido (se ve en el estado del correo y en los logs).</li>
 *   <li>Notificaciones automáticas, rebotes y listas de difusión se descartan sin guardar ni bajar adjuntos.</li>
 *   <li>Solo entra a "Por revisar" el mail de un remitente conocido y verificado ({@link RemitenteService}); el de un
 *       desconocido se guarda aparte, sin adjuntos, y el de un bloqueado se ignora.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "gs.correo.habilitado", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class CorreoLector {

    private static final Pattern REMITENTE_AUTOMATICO = Pattern.compile(
            "(?i)(^|[._+-])(no-?reply|noreply|do-?not-?reply|mailer-daemon|postmaster|bounces?)([._+@-]|$)|@accounts\\.google\\.com$");

    private final CorreoProperties props;
    private final RecepcionSolicitudService recepcion;
    private final RemitenteService remitentes;
    private final ConfirmacionCorreo confirmacion;
    private final EstadoCorreo estado;

    private final Map<String, Integer> fallos = new ConcurrentHashMap<>();
    private final AtomicBoolean enCurso = new AtomicBoolean(false);

    @PostConstruct
    void iniciar() {
        estado.habilitar();
        if (props.getHost().isBlank() || props.getUsuario().isBlank()) {
            log.warn("[CORREO] Habilitado pero faltan GS_CORREO_HOST o GS_CORREO_USUARIO: no se va a poder leer la casilla.");
        } else {
            log.info("[CORREO] Leyendo {} ({}) cada {} s.", props.getUsuario(), props.getHost(), props.getIntervaloSegundos());
        }
    }

    /** Un ciclo de lectura. Nunca lanza: lo que falle queda en {@link EstadoCorreo} y en el log. */
    @Scheduled(initialDelay = 20, fixedDelayString = "${gs.correo.intervalo-segundos:120}", timeUnit = TimeUnit.SECONDS)
    public void sondear() {
        if (!enCurso.compareAndSet(false, true)) return;   // un ciclo lento no se pisa con el siguiente
        try {
            if (props.getHost().isBlank() || props.getUsuario().isBlank()) {
                estado.error("Faltan GS_CORREO_HOST o GS_CORREO_USUARIO");
                return;
            }
            estado.exito(leerNuevos());
        } catch (Exception e) {
            log.warn("[CORREO] No se pudo leer la casilla: {}", e.getMessage());
            estado.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            enCurso.set(false);
        }
    }

    /** @return cuántas solicitudes nuevas se crearon */
    int leerNuevos() throws MessagingException {
        Session session = Session.getInstance(propiedades());
        try (Store store = session.getStore(props.isSsl() ? "imaps" : "imap")) {
            store.connect(props.getHost(), props.getPuerto(), props.getUsuario(), props.getClave());
            Folder carpeta = store.getFolder(props.getCarpeta());
            carpeta.open(Folder.READ_WRITE);
            try {
                int nuevas = 0;
                for (Message m : carpeta.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false))) {
                    if (procesar((MimeMessage) m)) nuevas++;
                }
                return nuevas;
            } finally {
                carpeta.close(false);
            }
        }
    }

    private Properties propiedades() {
        Properties p = new Properties();
        String proto = props.isSsl() ? "imaps" : "imap";
        p.put("mail." + proto + ".connectiontimeout", "15000");
        p.put("mail." + proto + ".timeout", "60000");
        // Leer el cuerpo NO debe marcar el mail como leído: eso se hace a mano, cuando la solicitud quedó guardada.
        p.put("mail." + proto + ".peek", "true");
        return p;
    }

    // ── Un mail ──────────────────────────────────────────────────────

    private boolean procesar(MimeMessage m) {
        String id = "?";
        try {
            id = idDe(m);
            String email = emailDe(m);
            if (esAutomatico(m, email)) {
                log.info("[CORREO] Se ignora un mail automático de {} (sin guardar).", email);
                estado.ignorado();
                marcarLeido(m);
                return false;
            }
            Boolean verificado = Autenticacion.remitenteVerificado(m.getHeader("Authentication-Results"), email, props.getVerificador());
            Clasificacion c = remitentes.clasificar(email, verificado);
            if (c.destino() == Destino.IGNORAR) {
                log.info("[CORREO] Se ignora un mail de {} (sin guardar): {}", email, c.motivo());
                estado.ignorado();
                marcarLeido(m);
                return false;
            }
            Resultado r = recepcion.registrar(leer(m, id, email, c.destino() == Destino.CONOCIDO), c);
            marcarLeido(m);   // recién ahora: la solicitud ya está guardada
            fallos.remove(id);
            if (!r.nueva()) return false;
            if (r.solicitud().getEstado() == SolicitudEntrante.Estado.SIN_AUTORIZAR) {
                estado.sinAutorizar();
                return false;
            }
            confirmacion.enviar(r.solicitud());
            return true;
        } catch (Exception e) {
            int n = fallos.merge(id, 1, Integer::sum);
            if (n >= props.getMaxFallos()) {
                log.error("[CORREO] El mail {} falló {} veces ({}): se da por leído para no trabar a los demás. Revisalo a mano.", id, n, e.getMessage());
                estado.fallido();
                fallos.remove(id);
                marcarLeido(m);
            } else {
                log.warn("[CORREO] No se pudo procesar el mail {} (intento {}/{}): {}", id, n, props.getMaxFallos(), e.getMessage());
            }
            return false;
        }
    }

    private void marcarLeido(MimeMessage m) {
        try {
            m.setFlag(Flags.Flag.SEEN, true);
        } catch (MessagingException e) {
            // No es grave: la solicitud ya está guardada y el Message-ID impide duplicarla en el próximo ciclo.
            log.warn("[CORREO] No se pudo marcar como leído: {}", e.getMessage());
        }
    }

    private boolean esAutomatico(MimeMessage m, String email) throws MessagingException {
        if (email != null && REMITENTE_AUTOMATICO.matcher(email).find()) return true;
        if (email != null) {
            for (String extra : props.getRemitentesIgnorados()) {
                if (Pattern.compile(extra, Pattern.CASE_INSENSITIVE).matcher(email).find()) return true;
            }
        }
        String auto = primero(m, "Auto-Submitted");
        if (auto != null && !auto.strip().equalsIgnoreCase("no")) return true;
        String precedencia = primero(m, "Precedence");
        if (precedencia != null && List.of("bulk", "list", "junk").contains(precedencia.strip().toLowerCase())) return true;
        return primero(m, "List-Unsubscribe") != null;
    }

    private static String primero(MimeMessage m, String cabecera) throws MessagingException {
        String[] v = m.getHeader(cabecera);
        return v == null || v.length == 0 ? null : v[0];
    }

    private static String idDe(MimeMessage m) throws MessagingException {
        String id = m.getMessageID();
        if (id != null && !id.isBlank()) return id.strip();
        // Sin Message-ID (raro): una huella del contenido que identifica al mail igual.
        try {
            String base = emailDe(m) + "|" + m.getSentDate() + "|" + m.getSubject() + "|" + m.getSize();
            return "sin-id-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(base.getBytes(StandardCharsets.UTF_8))).substring(0, 40);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String emailDe(MimeMessage m) throws MessagingException {
        Address[] de = m.getFrom();
        return de != null && de.length > 0 && de[0] instanceof InternetAddress ia && ia.getAddress() != null
                ? ia.getAddress().strip().toLowerCase() : null;
    }

    private static String nombreDe(MimeMessage m) throws MessagingException {
        Address[] de = m.getFrom();
        return de != null && de.length > 0 && de[0] instanceof InternetAddress ia ? ia.getPersonal() : null;
    }

    // ── Contenido ────────────────────────────────────────────────────

    private static final class Acumulador {
        final StringBuilder texto = new StringBuilder();
        final StringBuilder html = new StringBuilder();
        final List<Adjunto> adjuntos = new ArrayList<>();
        /** false = solo se anotan los nombres de los adjuntos, sin bajarlos (remitente sin autorizar). */
        final boolean bajarAdjuntos;

        Acumulador(boolean bajarAdjuntos) {
            this.bajarAdjuntos = bajarAdjuntos;
        }
    }

    private DatosCorreo leer(MimeMessage m, String id, String email, boolean bajarAdjuntos) throws MessagingException, IOException {
        Acumulador acc = new Acumulador(bajarAdjuntos);
        recolectar(m, acc);
        String cuerpo = acc.texto.toString().isBlank() ? htmlATexto(acc.html.toString()) : acc.texto.toString();
        LocalDateTime fecha = m.getSentDate() == null ? null
                : LocalDateTime.ofInstant(m.getSentDate().toInstant(), ZoneId.systemDefault());
        return new DatosCorreo(id, email, nombreDe(m), m.getSubject(), cuerpo.strip(), fecha, acc.adjuntos);
    }

    private void recolectar(Part p, Acumulador acc) throws MessagingException, IOException {
        if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            for (int i = 0; i < mp.getCount(); i++) recolectar(mp.getBodyPart(i), acc);
            return;
        }
        String nombre = nombreAdjunto(p);
        boolean esAdjunto = Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition()) || nombre != null;
        if (esAdjunto) {
            leerAdjunto(p, nombre == null ? "adjunto" : nombre, acc);
        } else if (p.isMimeType("text/plain")) {
            acc.texto.append(p.getContent()).append('\n');
        } else if (p.isMimeType("text/html")) {
            acc.html.append(p.getContent()).append('\n');
        }
    }

    private static String nombreAdjunto(Part p) throws MessagingException {
        String n = p.getFileName();
        if (n == null) return null;
        try {
            return MimeUtility.decodeText(n);
        } catch (java.io.UnsupportedEncodingException e) {
            return n;
        }
    }

    private void leerAdjunto(Part p, String nombre, Acumulador acc) throws MessagingException, IOException {
        if (!acc.bajarAdjuntos) {
            acc.adjuntos.add(new Adjunto(nombre, null, "no se guardó porque el remitente todavía no está autorizado"));
            return;
        }
        long max = props.getMaxAdjuntoMb() * 1024L * 1024L;
        int declarado = p.getSize();
        if (declarado > max) {
            acc.adjuntos.add(new Adjunto(nombre, null, "pesa " + (declarado / 1024 / 1024) + " MB y el máximo es " + props.getMaxAdjuntoMb() + " MB"));
            return;
        }
        try (InputStream in = p.getInputStream()) {
            byte[] datos = in.readNBytes((int) max + 1);
            if (datos.length > max) {
                acc.adjuntos.add(new Adjunto(nombre, null, "supera los " + props.getMaxAdjuntoMb() + " MB permitidos"));
            } else {
                acc.adjuntos.add(new Adjunto(nombre, datos, null));
            }
        }
    }

    /** HTML a texto plano, sin librerías: saltos en los cortes de bloque, sin etiquetas y con las entidades comunes. */
    static String htmlATexto(String html) {
        if (html == null || html.isBlank()) return "";
        String t = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>|</p>|</div>|</li>|</tr>", "\n")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        return t.replaceAll("[ \\t]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").strip();
    }
}
