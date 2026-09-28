package com.gs.monolito.auth.service;

import com.gs.monolito.auth.dto.RegisterRequest;
import com.gs.monolito.auth.model.Rol;
import com.gs.monolito.auth.model.Usuario;
import com.gs.monolito.auth.repository.UsuarioRepository;
import com.gs.monolito.finanzas.dto.CrearEmpleadoRequest;
import com.gs.monolito.finanzas.exception.ConflictException;
import com.gs.monolito.finanzas.model.FrecuenciaPago;
import com.gs.monolito.finanzas.service.IGestionSueldoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Servicio de gestión del ciclo de vida de usuarios del Laboratorio G&amp;S.
 */
@Service
public class UsuarioService {

    private static final Logger log = LoggerFactory.getLogger(UsuarioService.class);

    /** Roles del laboratorio que cobran sueldo. ODONTOLOGO es cliente, no empleado. */
    private static final Set<Rol> ROLES_EMPLEADO = EnumSet.of(Rol.TECNICO, Rol.ADMINISTRATIVO, Rol.ADMIN);

    private static final String ALFABETO_PASSWORD_TEMPORAL =
        "ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final IGestionSueldoService gestionSueldoService;

    public UsuarioService(UsuarioRepository usuarioRepository, PasswordEncoder passwordEncoder,
                          IGestionSueldoService gestionSueldoService) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
        this.gestionSueldoService = gestionSueldoService;
    }

    /**
     * Alta de un usuario por el ADMIN. Queda activo de entrada: el ADMIN es el
     * único que administra usuarios y roles, así que no hace falta que otro lo
     * apruebe (el esquema anterior de "crea ADMIN, activa ADMINISTRATIVO"
     * terminaba dándole al ADMINISTRATIVO poder sobre las cuentas, incluida la
     * del ADMIN).
     */
    public Usuario registrar(RegisterRequest request) {
        if (usuarioRepository.existsByUsername(request.username())) {
            throw new IllegalArgumentException("El nombre de usuario ya está en uso.");
        }

        Usuario nuevo = Usuario.builder()
            .nombre(request.nombre())
            .apellido(request.apellido())
            .username(request.username())
            .password(passwordEncoder.encode(request.password()))
            .rol(request.rol())
            .enabled(true)
            .pendienteAprobacion(false)
            .build();

        Usuario guardado = usuarioRepository.save(nuevo);
        provisionarSueldoSiCorresponde(guardado);
        return guardado;
    }

    public List<Usuario> listarTodos() {
        return usuarioRepository.findAll();
    }

    public Usuario aprobar(Long id) {
        Usuario usuario = usuarioRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Usuario no encontrado."));
        usuario.setEnabled(true);
        usuario.setPendienteAprobacion(false);
        Usuario guardado = usuarioRepository.save(usuario);
        provisionarSueldoSiCorresponde(guardado);
        return guardado;
    }

    /**
     * @param actor username de quien hace el cambio — no puede desactivarse a
     *              sí mismo (se quedaría afuera sin que nadie pueda reactivarlo).
     * @throws IllegalStateException si deja al sistema sin ningún ADMIN activo.
     */
    public Usuario cambiarEstado(Long id, boolean activo, String actor) {
        Usuario usuario = usuarioRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Usuario no encontrado."));
        if (!activo) {
            if (usuario.getUsername().equals(actor)) {
                throw new IllegalStateException("No podés desactivar tu propia cuenta.");
            }
            if (esUltimoAdminActivo(usuario)) {
                throw new IllegalStateException("No se puede desactivar al único Administrador activo.");
            }
        }
        usuario.setEnabled(activo);
        usuario.setPendienteAprobacion(false);
        Usuario guardado = usuarioRepository.save(usuario);
        if (activo) provisionarSueldoSiCorresponde(guardado);
        return guardado;
    }

    /**
     * Cambia el rol de otro usuario (exclusivo del ADMIN, ver AuthSecurityConfig).
     *
     * @param actor username de quien hace el cambio — no puede cambiarse su
     *              propio rol (evita que el único ADMIN se degrade por error).
     * @throws IllegalStateException si deja al sistema sin ningún ADMIN activo.
     */
    public Usuario cambiarRol(Long id, Rol nuevoRol, String actor) {
        Usuario usuario = usuarioRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Usuario no encontrado."));
        if (usuario.getUsername().equals(actor)) {
            throw new IllegalStateException("No podés cambiar tu propio rol.");
        }
        if (usuario.getRol() == nuevoRol) return usuario;
        if (nuevoRol != Rol.ADMIN && esUltimoAdminActivo(usuario)) {
            throw new IllegalStateException("No se puede quitar el rol al único Administrador activo.");
        }
        usuario.setRol(nuevoRol);
        Usuario guardado = usuarioRepository.save(usuario);
        if (guardado.isEnabled()) provisionarSueldoSiCorresponde(guardado);
        return guardado;
    }

    private boolean esUltimoAdminActivo(Usuario u) {
        return u.getRol() == Rol.ADMIN && u.isEnabled()
            && usuarioRepository.countByRolAndEnabledTrue(Rol.ADMIN) <= 1;
    }

    /**
     * Da de alta automáticamente al empleado en el módulo de finanzas apenas
     * se activa su cuenta — antes llamaba por Feign a ms-finanzas, ahora
     * directo al Service en el mismo proceso (ver CrearEmpleadoRequest).
     * Sin frecuencia/monto base todavía (el admin los configura después desde
     * Finanzas → Sueldos): queda dado de alta con $0/MENSUAL como placeholder,
     * así no se olvida — antes de esto, nada avisaba que faltaba configurarlo.
     * Best-effort, como el resto de las llamadas entre módulos de este
     * monolito: nunca debe hacer fallar la activación del usuario.
     */
    private void provisionarSueldoSiCorresponde(Usuario u) {
        if (!ROLES_EMPLEADO.contains(u.getRol())) return;
        try {
            CrearEmpleadoRequest req = new CrearEmpleadoRequest();
            req.setUsuarioId(u.getId());
            req.setNombre(u.getNombre() + " " + u.getApellido());
            req.setRol(u.getRol().name());
            req.setTelefono(u.getTelefono());
            req.setFrecuencia(FrecuenciaPago.MENSUAL);
            req.setMontoBase(BigDecimal.ZERO);
            gestionSueldoService.crearEmpleado(req);
            log.info("[GS-AUTH] Usuario {} dado de alta en sueldos (pendiente configurar frecuencia/monto real).",
                    u.getUsername());
        } catch (ConflictException e) {
            log.debug("[GS-AUTH] Usuario {} ya estaba dado de alta en sueldos — nada que hacer.", u.getUsername());
        } catch (Exception e) {
            log.warn("[GS-AUTH] No se pudo dar de alta en sueldos al usuario {}: {} (se puede completar a mano "
                    + "desde Finanzas → Sueldos → \"Nuevo empleado\").", u.getUsername(), e.getMessage());
        }
    }

    public Usuario actualizarTelefono(Long id, String telefono) {
        Usuario usuario = usuarioRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Usuario no encontrado."));
        usuario.setTelefono(telefono != null && !telefono.isBlank() ? telefono.trim() : null);
        return usuarioRepository.save(usuario);
    }

    // ── Perfil propio (self-service) ─────────────────────────────

    public Usuario buscarPorUsername(String username) {
        return usuarioRepository.findByUsername(username)
            .orElseThrow(() -> new RuntimeException("Usuario no encontrado."));
    }

    public Usuario actualizarPerfil(String username, String nombre, String apellido, String telefono) {
        Usuario u = buscarPorUsername(username);
        if (nombre != null && !nombre.isBlank())   u.setNombre(nombre.trim());
        if (apellido != null && !apellido.isBlank()) u.setApellido(apellido.trim());
        u.setTelefono(telefono != null && !telefono.isBlank() ? telefono.trim() : null);
        return usuarioRepository.save(u);
    }

    public Usuario cambiarPassword(String username, String actual, String nueva) {
        Usuario u = buscarPorUsername(username);
        if (!passwordEncoder.matches(actual, u.getPassword())) {
            throw new IllegalArgumentException("La contraseña actual no es correcta.");
        }
        u.setPassword(passwordEncoder.encode(nueva));
        u.setDebeCambiarPassword(false);
        return usuarioRepository.save(u);
    }

    /**
     * Resetea la contraseña de otro usuario (ADMIN). Genera una temporal
     * aleatoria, la devuelve en texto plano UNA sola vez (para que el ADMIN se
     * la pase al empleado) y marca la cuenta para forzar el cambio en el
     * próximo login. No hay envío de mail: este sistema no tiene SMTP
     * configurado, así que la entrega es manual (de palabra, WhatsApp, etc.).
     */
    public String resetearPassword(Long id) {
        Usuario usuario = usuarioRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Usuario no encontrado."));
        String temporal = generarPasswordTemporal();
        usuario.setPassword(passwordEncoder.encode(temporal));
        usuario.setDebeCambiarPassword(true);
        usuarioRepository.save(usuario);
        log.info("[GS-AUTH] Contraseña reseteada por el ADMIN para el usuario {}", usuario.getUsername());
        return temporal;
    }

    private String generarPasswordTemporal() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(ALFABETO_PASSWORD_TEMPORAL.charAt(RANDOM.nextInt(ALFABETO_PASSWORD_TEMPORAL.length())));
        }
        return sb.toString();
    }

    public Usuario aceptarTerminos(String username) {
        Usuario u = buscarPorUsername(username);
        u.setTerminosAceptados(true);
        u.setFechaAceptacionTerminos(Instant.now());
        return usuarioRepository.save(u);
    }
}
