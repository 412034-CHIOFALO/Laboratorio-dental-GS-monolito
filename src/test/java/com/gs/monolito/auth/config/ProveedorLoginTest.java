package com.gs.monolito.auth.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Quien no sabe la contraseña recibe siempre "credenciales incorrectas",
 * aunque la cuenta exista y esté desactivada: el estado de la cuenta se
 * revela recién con la contraseña correcta.
 */
class ProveedorLoginTest {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private DaoAuthenticationProvider proveedorConUsuarioDesactivado() {
        UserDetailsService uds = username -> User.withUsername("exempleado")
                .password(encoder.encode("clave-correcta"))
                .roles("TECNICO")
                .disabled(true)
                .build();
        return AuthSecurityConfig.proveedorLogin(uds, encoder);
    }

    @Test
    void cuentaDesactivadaConPasswordIncorrecta_respondeCredencialesIncorrectas() {
        assertThatThrownBy(() -> proveedorConUsuarioDesactivado().authenticate(
                new UsernamePasswordAuthenticationToken("exempleado", "cualquiera")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void cuentaDesactivadaConPasswordCorrecta_recienAhiDiceDesactivada() {
        assertThatThrownBy(() -> proveedorConUsuarioDesactivado().authenticate(
                new UsernamePasswordAuthenticationToken("exempleado", "clave-correcta")))
                .isInstanceOf(DisabledException.class);
    }
}
