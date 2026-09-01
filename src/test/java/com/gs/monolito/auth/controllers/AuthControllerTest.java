package com.gs.monolito.auth.controllers;

import com.gs.monolito.auth.model.Rol;
import com.gs.monolito.auth.model.Usuario;
import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.auth.service.UsuarioService;
import com.gs.monolito.common.security.JwtCookieAuthenticationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Login es el flujo con más plata en juego de todo auth: acá se decide quién
 * entra, qué token se firma, y qué queda en la bitácora. Verifica en
 * particular que el JWT SOLO viaja en la cookie httpOnly (nunca en el body —
 * ver el bug de CSRF/cookie que se corrigió en la migración a cookies) y que
 * tanto los éxitos como los fallos quedan auditados.
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock private JwtEncoder jwtEncoder;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private UsuarioService usuarioService;
    @Mock private AuditoriaService auditoriaService;

    private AuthController controller;
    private final MockHttpServletRequest httpRequest = new MockHttpServletRequest();

    @BeforeEach
    void setUp() {
        controller = new AuthController(jwtEncoder, authenticationManager, usuarioService, auditoriaService);
        ReflectionTestUtils.setField(controller, "tokenTtlHours", 12L);
        ReflectionTestUtils.setField(controller, "issuer", "http://test-issuer");
        ReflectionTestUtils.setField(controller, "cookieSecure", true);
    }

    @Test
    void loginExitoso_devuelveTokenSoloEnCookieHttpOnly_nuncaEnElBody() {
        UsernamePasswordAuthenticationToken authOk = new UsernamePasswordAuthenticationToken(
                "admin", "123456", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        when(authenticationManager.authenticate(any())).thenReturn(authOk);

        Jwt jwtMock = mock(Jwt.class);
        when(jwtMock.getTokenValue()).thenReturn("token-secreto-de-prueba");
        when(jwtEncoder.encode(any())).thenReturn(jwtMock);

        Usuario u = Usuario.builder()
                .username("admin").rol(Rol.ADMIN)
                .terminosAceptados(true).debeCambiarPassword(false)
                .build();
        when(usuarioService.buscarPorUsername("admin")).thenReturn(u);

        ResponseEntity<?> resp = controller.login(
                new AuthController.LoginRequest("admin", "123456"), httpRequest);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        String setCookie = resp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .contains(JwtCookieAuthenticationFilter.COOKIE_NAME + "=token-secreto-de-prueba")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsEntry("rol", "ADMIN").containsEntry("terminosAceptados", true);
        assertThat(body.toString()).doesNotContain("token-secreto-de-prueba");

        verify(auditoriaService).registrar(eq("admin"), eq("LOGIN"), any(), any(), any());
    }

    @Test
    void loginConCredencialesIncorrectas_devuelve401YAuditaElFallo() {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("mal"));

        ResponseEntity<?> resp = controller.login(
                new AuthController.LoginRequest("admin", "cualquiera"), httpRequest);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(auditoriaService).registrar(eq("admin"), eq("LOGIN_FALLIDO"), any(), any(), any());
        verifyNoInteractions(jwtEncoder);
    }

    @Test
    void loginConCuentaPendienteDeAprobacion_devuelve403YAuditaElFallo() {
        when(authenticationManager.authenticate(any())).thenThrow(new DisabledException("pendiente"));

        ResponseEntity<?> resp = controller.login(
                new AuthController.LoginRequest("nuevo", "123456"), httpRequest);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(auditoriaService).registrar(eq("nuevo"), eq("LOGIN_FALLIDO"), any(), any(), any());
        verifyNoInteractions(jwtEncoder);
    }
}
