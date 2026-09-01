package com.gs.monolito.auth.service;

import com.gs.monolito.auth.dto.RegisterRequest;
import com.gs.monolito.auth.model.Rol;
import com.gs.monolito.auth.model.Usuario;
import com.gs.monolito.auth.repository.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

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

    public UsuarioService(UsuarioRepository usuarioRepository, PasswordEncoder passwordEncoder) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
    }

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
            .enabled(false)
            .pendienteAprobacion(true)
            .build();

        return usuarioRepository.save(nuevo);
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

    public Usuario cambiarEstado(Long id, boolean activo) {
        Usuario usuario = usuarioRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Usuario no encontrado."));
        usuario.setEnabled(activo);
        usuario.setPendienteAprobacion(false);
        Usuario guardado = usuarioRepository.save(usuario);
        if (activo) provisionarSueldoSiCorresponde(guardado);
        return guardado;
    }

    /**
     * Da de alta automáticamente al empleado en el módulo de finanzas apenas
     * se activa su cuenta.
     *
     * TODO(Etapa 4): esto llamaba por Feign a ms-finanzas
     * (POST /api/finanzas/sueldos/empleados, con fallback best-effort si no
     * respondía). El módulo finanzas todavía no existe en este repo — cuando
     * se porte (Etapa 4), reemplazar este log por una llamada directa e
     * in-process al Service de finanzas que dé de alta al empleado. Hasta
     * entonces se comporta igual que el fallback de siempre: nunca hace
     * fallar la activación, y el alta se puede completar a mano.
     */
    private void provisionarSueldoSiCorresponde(Usuario u) {
        if (!ROLES_EMPLEADO.contains(u.getRol())) return;
        log.warn("[GS-AUTH] Alta automática en sueldos pendiente de implementar (Etapa 4) para el usuario {} "
                + "({}). Se puede completar a mano desde Finanzas → Sueldos → \"Nuevo empleado\" una vez portado ese módulo.",
                u.getId(), u.getUsername());
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
        log.info("[GS-AUTH] Contraseña reseteada por ADMIN para el usuario {}", usuario.getUsername());
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
