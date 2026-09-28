package com.gs.monolito.auth.service;

import com.gs.monolito.auth.dto.RegisterRequest;
import com.gs.monolito.auth.model.Rol;
import com.gs.monolito.auth.model.Usuario;
import com.gs.monolito.auth.repository.UsuarioRepository;
import com.gs.monolito.finanzas.service.IGestionSueldoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Reglas del ciclo de vida de usuarios: el ADMIN crea usuarios ya activos y
 * nunca puede dejar al sistema sin un ADMIN activo (ni por baja ni por cambio
 * de rol), ni tocarse a sí mismo el rol o el estado.
 */
@ExtendWith(MockitoExtension.class)
class UsuarioServiceTest {

    @Mock private UsuarioRepository usuarioRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private IGestionSueldoService gestionSueldoService;

    private UsuarioService service;

    @BeforeEach
    void setUp() {
        service = new UsuarioService(usuarioRepository, passwordEncoder, gestionSueldoService);
    }

    private Usuario usuario(long id, String username, Rol rol, boolean enabled) {
        return Usuario.builder().id(id).username(username).nombre("N").apellido("A")
                .rol(rol).enabled(enabled).build();
    }

    @Test
    void registrar_dejaAlUsuarioActivoSinAprobacionDeOtro() {
        when(usuarioRepository.existsByUsername("jperez")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("hash");
        when(usuarioRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Usuario nuevo = service.registrar(new RegisterRequest("Juan", "Pérez", "jperez", "clave-segura", Rol.TECNICO));

        assertThat(nuevo.isEnabled()).isTrue();
        assertThat(nuevo.isPendienteAprobacion()).isFalse();
        verify(gestionSueldoService).crearEmpleado(any());
    }

    @Test
    void cambiarEstado_noPermiteDesactivarseASiMismo() {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuario(1, "admin", Rol.ADMIN, true)));

        assertThatThrownBy(() -> service.cambiarEstado(1L, false, "admin"))
                .isInstanceOf(IllegalStateException.class);
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void cambiarEstado_noPermiteDesactivarAlUnicoAdminActivo() {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuario(1, "admin", Rol.ADMIN, true)));
        when(usuarioRepository.countByRolAndEnabledTrue(Rol.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> service.cambiarEstado(1L, false, "otroAdmin"))
                .isInstanceOf(IllegalStateException.class);
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void cambiarEstado_desactivaAOtroUsuarioNormalmente() {
        when(usuarioRepository.findById(5L)).thenReturn(Optional.of(usuario(5, "tecnico1", Rol.TECNICO, true)));
        when(usuarioRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Usuario u = service.cambiarEstado(5L, false, "admin");

        assertThat(u.isEnabled()).isFalse();
    }

    @Test
    void cambiarRol_noPermiteCambiarseElPropioRol() {
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuario(1, "admin", Rol.ADMIN, true)));

        assertThatThrownBy(() -> service.cambiarRol(1L, Rol.TECNICO, "admin"))
                .isInstanceOf(IllegalStateException.class);
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    void cambiarRol_noPermiteQuitarleElRolAlUnicoAdminActivo() {
        when(usuarioRepository.findById(2L)).thenReturn(Optional.of(usuario(2, "admin2", Rol.ADMIN, true)));
        when(usuarioRepository.countByRolAndEnabledTrue(Rol.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> service.cambiarRol(2L, Rol.ADMINISTRATIVO, "admin"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cambiarRol_conOtroAdminActivo_permiteDegradarlo() {
        when(usuarioRepository.findById(2L)).thenReturn(Optional.of(usuario(2, "admin2", Rol.ADMIN, true)));
        when(usuarioRepository.countByRolAndEnabledTrue(Rol.ADMIN)).thenReturn(2L);
        when(usuarioRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Usuario u = service.cambiarRol(2L, Rol.ADMINISTRATIVO, "admin");

        assertThat(u.getRol()).isEqualTo(Rol.ADMINISTRATIVO);
    }

    @Test
    void cambiarRol_promueveAOtroUsuario() {
        when(usuarioRepository.findById(5L)).thenReturn(Optional.of(usuario(5, "tecnico1", Rol.TECNICO, true)));
        when(usuarioRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Usuario u = service.cambiarRol(5L, Rol.ADMINISTRATIVO, "admin");

        assertThat(u.getRol()).isEqualTo(Rol.ADMINISTRATIVO);
    }
}
