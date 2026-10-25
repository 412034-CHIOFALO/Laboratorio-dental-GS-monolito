package com.gs.monolito.pedidos.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.common.security.CurrentUser;
import com.gs.monolito.pedidos.config.CorreoProperties;
import com.gs.monolito.pedidos.exception.BusinessException;
import com.gs.monolito.pedidos.exception.ResourceNotFoundException;
import com.gs.monolito.pedidos.model.Odontologo;
import com.gs.monolito.pedidos.model.RemitenteAutorizado;
import com.gs.monolito.pedidos.model.SolicitudEntrante;
import com.gs.monolito.pedidos.model.SolicitudEntrante.RemitenteTipo;
import com.gs.monolito.pedidos.repository.OdontologoRepository;
import com.gs.monolito.pedidos.repository.RemitenteAutorizadoRepository;
import com.gs.monolito.pedidos.repository.SolicitudEntranteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Decide qué hacer con un mail según quién lo manda. Antes todo mail iba a "Por revisar", incluido el spam. Ahora:
 *
 * <ul>
 *   <li><b>Conocido</b>: el email está cargado en la ficha de un odontólogo, o alguien del laboratorio lo autorizó
 *       (una secretaria, otra casilla del mismo odontólogo). Entra directo a "Por revisar" con sus adjuntos.</li>
 *   <li><b>Sin autorizar</b>: cualquier otra dirección, o una conocida cuyo mail no pudo verificarse (alguien podría
 *       estar haciéndose pasar por ella). Se guarda aparte, sin adjuntos y con vencimiento, hasta que una persona
 *       decide. Cada remitente tiene un tope para que nadie pueda llenar la bandeja.</li>
 *   <li><b>Ignorar</b>: bloqueado por el laboratorio, o pasó los topes. No se guarda nada.</li>
 * </ul>
 *
 * <p>Que un mail diga venir de un odontólogo no prueba que lo sea. Por eso un remitente nunca se "aprende" solo: la
 * autorización siempre es una decisión de una persona del laboratorio.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RemitenteService {

    public enum Destino { CONOCIDO, SIN_AUTORIZAR, IGNORAR }

    /**
     * @param tipo/odontologoId/odontologoNombre/etiqueta quién es o dice ser el remitente (en SIN_AUTORIZAR por
     *        verificación fallida, es lo que <i>afirma</i> ser, no algo confirmado)
     * @param motivo por qué quedó sin autorizar o se ignoró (null si es conocido)
     */
    public record Clasificacion(Destino destino, RemitenteTipo tipo, Long odontologoId, String odontologoNombre,
                                String etiqueta, String motivo, Boolean verificado) {

        public static Clasificacion conocido(RemitenteTipo tipo, Long odontologoId, String odontologoNombre, String etiqueta, Boolean verificado) {
            return new Clasificacion(Destino.CONOCIDO, tipo, odontologoId, odontologoNombre, etiqueta, null, verificado);
        }

        public static Clasificacion sinAutorizar(String motivo, Boolean verificado) {
            return new Clasificacion(Destino.SIN_AUTORIZAR, null, null, null, null, motivo, verificado);
        }

        public static Clasificacion ignorar(String motivo) {
            return new Clasificacion(Destino.IGNORAR, null, null, null, null, motivo, null);
        }
    }

    private final RemitenteAutorizadoRepository remitenteRepo;
    private final OdontologoRepository odontologoRepo;
    private final SolicitudEntranteRepository solicitudRepo;
    private final AuditoriaService auditoria;
    private final CorreoProperties props;
    private final Clock reloj;

    /**
     * @param verificado resultado de SPF/DKIM/DMARC según el servidor de correo (ver {@link Autenticacion}); null si
     *                   el servidor no informó nada
     */
    public Clasificacion clasificar(String email, Boolean verificado) {
        if (email == null || email.isBlank()) {
            return limitar(Clasificacion.sinAutorizar("El mail no trae una dirección de remitente.", verificado), "sin-remitente");
        }
        String dir = email.strip().toLowerCase();

        Optional<RemitenteAutorizado> regla = remitenteRepo.findByEmailIgnoreCase(dir);
        if (regla.isPresent() && regla.get().getEstado() == RemitenteAutorizado.Estado.BLOQUEADO) {
            return Clasificacion.ignorar("Remitente bloqueado por el laboratorio.");
        }

        Clasificacion conocido = regla.map(this::deRegla).orElseGet(() -> deFicha(dir));
        if (conocido == null) {
            String motivo = Boolean.FALSE.equals(verificado)
                    ? "Remitente desconocido, y el servidor de correo no pudo comprobar que el mail venga de esa dirección."
                    : "Remitente desconocido: su dirección no está en la ficha de ningún odontólogo ni fue autorizada.";
            return limitar(Clasificacion.sinAutorizar(motivo, verificado), dir);
        }

        if (props.isExigirVerificacion() && !Boolean.TRUE.equals(verificado)) {
            String motivo = Boolean.FALSE.equals(verificado)
                    ? "La dirección es conocida, pero el servidor de correo no pudo comprobar que el mail venga realmente de ella (podría ser alguien haciéndose pasar por esa persona)."
                    : "La dirección es conocida, pero el servidor de correo no informó si el mail es auténtico.";
            return limitar(new Clasificacion(Destino.SIN_AUTORIZAR, conocido.tipo(), conocido.odontologoId(),
                    conocido.odontologoNombre(), conocido.etiqueta(), motivo, verificado), dir);
        }
        return Clasificacion.conocido(conocido.tipo(), conocido.odontologoId(), conocido.odontologoNombre(), conocido.etiqueta(), verificado);
    }

    /** Los topes: por remitente (en los últimos días) y en total. Pasados, el mail se ignora en vez de guardarse. */
    private Clasificacion limitar(Clasificacion c, String remitente) {
        if (solicitudRepo.countByEstado(SolicitudEntrante.Estado.SIN_AUTORIZAR) >= props.getMaxCuarentenaTotal()) {
            return Clasificacion.ignorar("Hay demasiados mails sin autorizar esperando (" + props.getMaxCuarentenaTotal() + "); se ignora el resto hasta que se revisen.");
        }
        long recientes = solicitudRepo.countByRemitenteEmailAndEstadoAndFechaRecepcionAfter(remitente,
                SolicitudEntrante.Estado.SIN_AUTORIZAR, LocalDateTime.now(reloj).minusDays(props.getDiasContadorCuarentena()));
        if (recientes >= props.getMaxCuarentenaPorRemitente()) {
            return Clasificacion.ignorar("Ese remitente ya dejó " + recientes + " mails sin autorizar; se ignoran los siguientes.");
        }
        return c;
    }

    private Clasificacion deRegla(RemitenteAutorizado r) {
        String nombre = null;
        if (r.getOdontologoId() != null) {
            nombre = odontologoRepo.findById(r.getOdontologoId()).map(Odontologo::getNombre).orElse(null);
        }
        return Clasificacion.conocido(r.getTipo(), r.getOdontologoId(), nombre, r.getEtiqueta(), null);
    }

    private Clasificacion deFicha(String dir) {
        List<Odontologo> coinciden = odontologoRepo.findByActivoTrueAndEmailIgnoreCase(dir);
        if (coinciden.isEmpty()) return null;
        // Si la casilla la comparten varios odontólogos no se adivina cuál es: lo elige quien revisa.
        if (coinciden.size() > 1) {
            return Clasificacion.conocido(RemitenteTipo.ODONTOLOGO, null, null, "Casilla compartida por varios odontólogos", null);
        }
        Odontologo o = coinciden.get(0);
        return Clasificacion.conocido(RemitenteTipo.ODONTOLOGO, o.getId(), o.getNombre(), o.getNombre(), null);
    }

    // ── Lo que decide una persona ────────────────────────────────────

    public List<RemitenteAutorizado> listar() {
        return remitenteRepo.findAllByOrderByEmailAsc();
    }

    /** Autoriza una dirección (o actualiza a quién representa). Devuelve la regla vigente. */
    @Transactional
    public RemitenteAutorizado autorizar(String email, RemitenteTipo tipo, Long odontologoId, String etiquetaManual) {
        String dir = normalizar(email);
        if (tipo == null) throw new BusinessException("Indicá quién es: odontólogo, secretaria u otro.");
        Odontologo o = null;
        if (tipo != RemitenteTipo.OTRO) {
            if (odontologoId == null) throw new BusinessException("Elegí a qué odontólogo corresponde.");
            o = odontologoRepo.findById(odontologoId).filter(x -> Boolean.TRUE.equals(x.getActivo()))
                    .orElseThrow(() -> new ResourceNotFoundException("Odontólogo", odontologoId));
        }
        RemitenteAutorizado r = remitenteRepo.findByEmailIgnoreCase(dir).orElseGet(RemitenteAutorizado::new);
        boolean nuevo = r.getId() == null;
        r.setEmail(dir);
        r.setTipo(tipo);
        r.setOdontologoId(o == null ? null : o.getId());
        r.setEtiqueta(etiqueta(tipo, o, etiquetaManual));
        r.setEstado(RemitenteAutorizado.Estado.AUTORIZADO);
        if (nuevo) {
            r.setCreadoPor(CurrentUser.usernameOrSistema());
            r.setFechaCreacion(LocalDateTime.now(reloj));
        }
        RemitenteAutorizado guardado = remitenteRepo.save(r);
        auditoria.registrar(CurrentUser.usernameOrSistema(), "SOLICITUD", "Remitente autorizado", dir, guardado.getEtiqueta());
        log.info("[CORREO] Remitente {} autorizado como {} ({}).", dir, tipo, guardado.getEtiqueta());
        return guardado;
    }

    /**
     * Bloquea una dirección: sus mails se ignoran y los que tenía esperando se descartan. No se puede bloquear la
     * dirección de un odontólogo ni una ya autorizada: habría que sacarla de la lista primero (evita bloquear a un
     * cliente por tocar el botón equivocado).
     */
    @Transactional
    public int bloquear(String email) {
        String dir = normalizar(email);
        Optional<RemitenteAutorizado> regla = remitenteRepo.findByEmailIgnoreCase(dir);
        if (!odontologoRepo.findByActivoTrueAndEmailIgnoreCase(dir).isEmpty()) {
            throw new BusinessException("Esa dirección es la de un odontólogo cargado en el sistema: no se puede bloquear.");
        }
        if (regla.isPresent() && regla.get().getEstado() == RemitenteAutorizado.Estado.AUTORIZADO) {
            throw new BusinessException("Esa dirección está autorizada. Quitala de la lista de autorizados si querés bloquearla.");
        }
        RemitenteAutorizado r = regla.orElseGet(RemitenteAutorizado::new);
        if (r.getId() == null) {
            r.setEmail(dir);
            r.setCreadoPor(CurrentUser.usernameOrSistema());
            r.setFechaCreacion(LocalDateTime.now(reloj));
        }
        r.setTipo(RemitenteTipo.OTRO);
        r.setOdontologoId(null);
        r.setEtiqueta("Bloqueado");
        r.setEstado(RemitenteAutorizado.Estado.BLOQUEADO);
        remitenteRepo.save(r);

        int descartadas = 0;
        for (SolicitudEntrante s : solicitudRepo.findByRemitenteEmailAndEstado(dir, SolicitudEntrante.Estado.SIN_AUTORIZAR)) {
            s.setEstado(SolicitudEntrante.Estado.DESCARTADA);
            s.setMotivoDescarte("Remitente bloqueado");
            s.setResueltoPor(CurrentUser.usernameOrSistema());
            s.setFechaResolucion(LocalDateTime.now(reloj));
            solicitudRepo.save(s);
            descartadas++;
        }
        auditoria.registrar(CurrentUser.usernameOrSistema(), "SOLICITUD", "Remitente bloqueado", dir,
                descartadas + " mail(s) sin autorizar descartado(s)");
        log.info("[CORREO] Remitente {} bloqueado; {} mail(s) descartado(s).", dir, descartadas);
        return descartadas;
    }

    /** Saca una dirección de la lista (autorizada o bloqueada): vuelve a ser un remitente desconocido. */
    @Transactional
    public void quitar(Long id) {
        RemitenteAutorizado r = remitenteRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Remitente", id));
        remitenteRepo.delete(r);
        auditoria.registrar(CurrentUser.usernameOrSistema(), "SOLICITUD",
                r.getEstado() == RemitenteAutorizado.Estado.BLOQUEADO ? "Remitente desbloqueado" : "Remitente quitado de los autorizados",
                r.getEmail(), r.getEtiqueta());
        log.info("[CORREO] Remitente {} quitado de la lista ({}).", r.getEmail(), r.getEstado());
    }

    /** Cómo se muestra a un remitente autorizado: "Secretaria de Dr. Pérez". */
    static String etiqueta(RemitenteTipo tipo, Odontologo o, String manual) {
        String base = switch (tipo) {
            case ODONTOLOGO -> o == null ? "Odontólogo" : o.getNombre();
            case SECRETARIA -> "Secretaria" + (o == null ? "" : " de " + o.getNombre());
            case OTRO -> manual == null || manual.isBlank() ? "Autorizado" : manual.strip();
        };
        if (tipo != RemitenteTipo.OTRO && manual != null && !manual.isBlank()) base = base + " (" + manual.strip() + ")";
        return base.length() <= 150 ? base : base.substring(0, 149) + "…";
    }

    private static String normalizar(String email) {
        if (email == null || !email.strip().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new BusinessException("La dirección de mail no es válida.");
        }
        return email.strip().toLowerCase();
    }
}
