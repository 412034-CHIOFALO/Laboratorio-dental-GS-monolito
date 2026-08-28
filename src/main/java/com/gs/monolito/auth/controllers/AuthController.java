package com.gs.monolito.auth.controllers;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;

import com.gs.monolito.auth.dto.RegisterRequest;
import com.gs.monolito.auth.dto.UsuarioResponse;
import com.gs.monolito.auth.model.Usuario;
import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.auth.service.UsuarioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.beans.factory.annotation.Value;
import java.time.Duration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Controlador REST para autenticación, registro y administración de usuarios del laboratorio G&amp;S.
 * <p>
 * Emite tokens JWT firmados con RS256 a partir de credenciales validadas contra la base de datos.
 * El ciclo de vida de un usuario es: registro (pendiente) → aprobación por ADMIN → habilitado.
 * </p>
 */
@Tag(name = "Autenticación y Usuarios", description = "Login JWT, registro, aprobación y gestión de usuarios del laboratorio")
@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private final JwtEncoder jwtEncoder;
    private final AuthenticationManager authenticationManager;
    private final UsuarioService usuarioService;
    private final AuditoriaService auditoriaService;

    @Value("${gs.auth.token-ttl-hours:12}")
    private long tokenTtlHours;

    @Value("${AUTH_ISSUER:http://localhost:8080}")
    private String issuer;

    public AuthController(JwtEncoder jwtEncoder,
                          AuthenticationManager authenticationManager,
                          UsuarioService usuarioService,
                          AuditoriaService auditoriaService) {
        this.jwtEncoder            = jwtEncoder;
        this.authenticationManager = authenticationManager;
        this.usuarioService        = usuarioService;
        this.auditoriaService      = auditoriaService;
    }

    @Operation(
        summary = "Autenticar usuario y obtener token JWT",
        description = "Valida las credenciales contra la base de datos. Si el usuario está habilitado y aprobado, " +
                      "devuelve un token JWT RS256 con los claims 'sub' (username) y 'roles'. " +
                      "El token tiene una vigencia configurable (por defecto 12 horas). " +
                      "Este endpoint es público y está sujeto a rate limiting (bucket4j)."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Login exitoso — devuelve access_token JWT",
            content = @Content(mediaType = "application/json",
                schema = @Schema(example = "{\"access_token\": \"eyJhbGciOiJSUzI1NiJ9...\"}"))),
        @ApiResponse(responseCode = "401", description = "Usuario o contraseña incorrectos", content = @Content(
            mediaType = "application/json",
            schema = @Schema(example = "{\"error\": \"Usuario o contraseña incorrectos.\"}"))),
        @ApiResponse(responseCode = "403", description = "Cuenta pendiente de aprobación por el administrador", content = @Content(
            mediaType = "application/json",
            schema = @Schema(example = "{\"error\": \"Tu cuenta está pendiente de aprobación por el administrador.\"}"))),
        @ApiResponse(responseCode = "429", description = "Demasiados intentos de login — rate limit alcanzado", content = @Content)
    })
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        try {
            Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
            );

            String roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));

            JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofHours(tokenTtlHours)))
                .subject(auth.getName())
                .claim("roles", roles)
                .build();

            String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

            auditoriaService.registrar(auth.getName(), "LOGIN", "Inicio de sesión",
                "Sesión", "Login exitoso · roles: " + roles);

            Usuario u = usuarioService.buscarPorUsername(auth.getName());
            return ResponseEntity.ok(Map.of(
                "access_token", token,
                "terminosAceptados", u.isTerminosAceptados()
            ));

        } catch (DisabledException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Tu cuenta está pendiente de aprobación por el administrador."));
        } catch (BadCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Usuario o contraseña incorrectos."));
        }
    }

    @Operation(
        summary = "Registrar nuevo usuario (requiere ADMIN)",
        description = "Crea un nuevo usuario en estado pendiente de aprobación. " +
                      "El usuario queda deshabilitado hasta que un ADMINISTRATIVO lo active con PUT /usuarios/{id}/aprobar " +
                      "(a propósito no puede ser el mismo ADMIN que lo creó). Requiere rol ADMIN (Bearer JWT)."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Solicitud de registro creada correctamente",
            content = @Content(mediaType = "application/json",
                schema = @Schema(example = "{\"mensaje\": \"Solicitud enviada. El administrador activará tu cuenta pronto.\", \"username\": \"jperez\"}"))),
        @ApiResponse(responseCode = "403", description = "Sin permisos de administrador", content = @Content),
        @ApiResponse(responseCode = "409", description = "El nombre de usuario ya está en uso", content = @Content(
            mediaType = "application/json",
            schema = @Schema(example = "{\"error\": \"El nombre de usuario ya está en uso.\"}")))
    })
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        try {
            Usuario nuevo = usuarioService.registrar(request);
            auditoriaService.registrar(nuevo.getUsername(), "CREAR", "Registro de usuario",
                "Usuario " + nuevo.getUsername(), "Rol: " + nuevo.getRol() + " · pendiente de aprobación");
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "mensaje", "Solicitud enviada. El administrador activará tu cuenta pronto.",
                "username", nuevo.getUsername()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", e.getMessage()));
        }
    }

    @Operation(
        summary = "Listar todos los usuarios (requiere ADMIN)",
        description = "Devuelve la lista completa de usuarios registrados en el sistema, " +
                      "incluyendo su estado (habilitado / pendiente de aprobación) y rol. " +
                      "Requiere rol ADMIN (Bearer JWT)."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Lista de usuarios devuelta correctamente",
            content = @Content(mediaType = "application/json")),
        @ApiResponse(responseCode = "403", description = "Sin permisos de administrador", content = @Content)
    })
    @GetMapping("/usuarios")
    public ResponseEntity<List<UsuarioResponse>> listarUsuarios() {
        List<UsuarioResponse> usuarios = usuarioService.listarTodos()
            .stream()
            .map(UsuarioResponse::from)
            .toList();
        return ResponseEntity.ok(usuarios);
    }

    @Operation(
        summary = "Aprobar (activar) un usuario pendiente (requiere ADMIN)",
        description = "Cambia el estado del usuario a habilitado y limpia la marca de pendiente de aprobación. " +
                      "A partir de ese momento el usuario puede iniciar sesión. " +
                      "Requiere rol ADMIN (Bearer JWT)."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Usuario activado correctamente",
            content = @Content(mediaType = "application/json",
                schema = @Schema(example = "{\"mensaje\": \"Usuario activado correctamente.\", \"username\": \"jperez\"}"))),
        @ApiResponse(responseCode = "403", description = "Sin permisos de administrador", content = @Content),
        @ApiResponse(responseCode = "404", description = "No encontrado — el ID no corresponde a ningún usuario", content = @Content(
            mediaType = "application/json",
            schema = @Schema(example = "{\"error\": \"Usuario no encontrado.\"}")))
    })
    @PutMapping("/usuarios/{id}/aprobar")
    public ResponseEntity<?> aprobar(
            @Parameter(description = "ID numérico del usuario a aprobar", required = true, example = "5")
            @PathVariable @Positive Long id,
            @AuthenticationPrincipal Jwt jwt) {
        if (!esAdministrativo(jwt)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Dar de alta un usuario requiere rol ADMINISTRATIVO."));
        }
        try {
            Usuario aprobado = usuarioService.aprobar(id);
            auditoriaService.registrar(jwt.getSubject(), "EDITAR", "Activación de usuario",
                "Usuario " + aprobado.getUsername(), "Cuenta activada por administrador");
            return ResponseEntity.ok(Map.of(
                "mensaje", "Usuario activado correctamente.",
                "username", aprobado.getUsername()
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", e.getMessage()));
        }
    }

    @Operation(
        summary = "Cambiar estado activo/inactivo de un usuario (requiere ADMIN)",
        description = "Habilita o deshabilita un usuario. Útil para gestionar bajas temporales o definitivas " +
                      "del personal del laboratorio sin eliminar el registro histórico. " +
                      "Body JSON: {@code {\"activo\": true}} o {@code {\"activo\": false}}. " +
                      "Requiere rol ADMIN (Bearer JWT)."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Estado actualizado — devuelve el UsuarioResponse actualizado",
            content = @Content(mediaType = "application/json")),
        @ApiResponse(responseCode = "403", description = "Sin permisos de administrador", content = @Content),
        @ApiResponse(responseCode = "404", description = "No encontrado — el ID no corresponde a ningún usuario", content = @Content(
            mediaType = "application/json",
            schema = @Schema(example = "{\"error\": \"Usuario no encontrado.\"}")))
    })
    @PatchMapping("/usuarios/{id}/estado")
    public ResponseEntity<?> cambiarEstado(
            @Parameter(description = "ID numérico del usuario", required = true, example = "5")
            @PathVariable @Positive Long id,
            @RequestBody Map<String, Boolean> body,
            @AuthenticationPrincipal Jwt jwt) {
        if (body == null || body.get("activo") == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "El campo 'activo' (true/false) es obligatorio."));
        }
        boolean activo = Boolean.TRUE.equals(body.get("activo"));
        if (activo && !esAdministrativo(jwt)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Dar de alta un usuario requiere rol ADMINISTRATIVO."));
        }
        try {
            Usuario u = usuarioService.cambiarEstado(id, activo);
            auditoriaService.registrar(jwt.getSubject(), "EDITAR", "Cambio de estado de usuario",
                "Usuario " + u.getUsername(), activo ? "Activado" : "Desactivado");
            return ResponseEntity.ok(UsuarioResponse.from(u));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    @Operation(
        summary = "Actualizar el teléfono de un usuario (requiere ADMIN)",
        description = "Registra o actualiza el número de teléfono del integrante del laboratorio. " +
                      "Este número es usado por el bot de WhatsApp para identificar al usuario que envía comprobantes " +
                      "(mapeo teléfono → usuario interno). Body JSON: {@code {\"telefono\": \"1155443322\"}}. " +
                      "Si el valor es nulo o vacío, se elimina el teléfono registrado. " +
                      "Requiere rol ADMIN (Bearer JWT)."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Teléfono actualizado — devuelve el UsuarioResponse actualizado",
            content = @Content(mediaType = "application/json")),
        @ApiResponse(responseCode = "403", description = "Sin permisos de administrador", content = @Content),
        @ApiResponse(responseCode = "404", description = "No encontrado — el ID no corresponde a ningún usuario", content = @Content(
            mediaType = "application/json",
            schema = @Schema(example = "{\"error\": \"Usuario no encontrado.\"}")))
    })
    @PatchMapping("/usuarios/{id}/telefono")
    public ResponseEntity<?> actualizarTelefono(
            @Parameter(description = "ID numérico del usuario", required = true, example = "5")
            @PathVariable @Positive Long id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal Jwt jwt) {
        String telefono = body.get("telefono");
        if (telefono != null && !telefono.isBlank()
                && !telefono.matches("^[0-9+()\\-\\s]{6,30}$")) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "El teléfono solo puede contener números y los símbolos + - ( )"));
        }
        try {
            Usuario u = usuarioService.actualizarTelefono(id, telefono);
            auditoriaService.registrar(jwt.getSubject(), "EDITAR", "Actualización de teléfono",
                "Usuario " + u.getUsername(), "Teléfono actualizado");
            return ResponseEntity.ok(UsuarioResponse.from(u));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    // ── Perfil propio (self-service) ─────────────────────────────

    @Operation(summary = "Ver mi perfil",
               description = "Devuelve los datos del usuario autenticado (según el JWT).")
    @ApiResponse(responseCode = "200", description = "Perfil del usuario actual")
    @GetMapping("/me")
    public ResponseEntity<?> miPerfil(@AuthenticationPrincipal Jwt jwt) {
        try {
            Usuario u = usuarioService.buscarPorUsername(jwt.getSubject());
            return ResponseEntity.ok(UsuarioResponse.from(u));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    @Operation(summary = "Editar mi perfil",
               description = "Actualiza los datos propios editables (nombre, apellido, teléfono). " +
                             "El username y el rol no se modifican acá.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Perfil actualizado"),
        @ApiResponse(responseCode = "404", description = "Usuario no encontrado")
    })
    @PatchMapping("/me")
    public ResponseEntity<?> editarMiPerfil(@Valid @RequestBody PerfilRequest request,
                                            @AuthenticationPrincipal Jwt jwt) {
        try {
            Usuario u = usuarioService.actualizarPerfil(
                jwt.getSubject(), request.nombre(), request.apellido(), request.telefono());
            auditoriaService.registrar(jwt.getSubject(), "EDITAR", "Actualización de perfil propio",
                "Usuario " + u.getUsername(), "Datos de contacto actualizados");
            return ResponseEntity.ok(UsuarioResponse.from(u));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    @Operation(summary = "Cambiar mi contraseña",
               description = "Cambia la contraseña propia. Requiere la contraseña actual para validar.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Contraseña actualizada"),
        @ApiResponse(responseCode = "400", description = "La contraseña actual no es correcta")
    })
    @PostMapping("/me/password")
    public ResponseEntity<?> cambiarMiPassword(@Valid @RequestBody CambioPasswordRequest request,
                                               @AuthenticationPrincipal Jwt jwt) {
        try {
            usuarioService.cambiarPassword(jwt.getSubject(), request.actual(), request.nueva());
            auditoriaService.registrar(jwt.getSubject(), "EDITAR", "Cambio de contraseña propia",
                "Usuario " + jwt.getSubject(), "Contraseña actualizada por el usuario");
            return ResponseEntity.ok(Map.of("mensaje", "Contraseña actualizada correctamente."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    @Operation(summary = "Aceptar los términos y condiciones",
               description = "Marca al usuario autenticado como habiendo aceptado los términos y condiciones " +
                             "del sistema, con fecha. Se pide una única vez, generalmente en su primer login.")
    @ApiResponse(responseCode = "200", description = "Términos aceptados")
    @PostMapping("/me/aceptar-terminos")
    public ResponseEntity<?> aceptarTerminos(@AuthenticationPrincipal Jwt jwt) {
        Usuario u = usuarioService.aceptarTerminos(jwt.getSubject());
        auditoriaService.registrar(jwt.getSubject(), "EDITAR", "Aceptación de términos y condiciones",
            "Usuario " + u.getUsername(), "Términos y condiciones aceptados");
        return ResponseEntity.ok(UsuarioResponse.from(u));
    }

    /** ¿El JWT del que llama tiene ROLE_ADMINISTRATIVO? */
    private boolean esAdministrativo(Jwt jwt) {
        String roles = jwt.getClaimAsString("roles");
        if (roles == null) return false;
        return Arrays.asList(roles.split(",")).contains("ROLE_ADMINISTRATIVO");
    }

    public record LoginRequest(
        @NotBlank(message = "El username no puede estar vacío")
        @Size(min = 3, max = 50, message = "El username debe tener entre 3 y 50 caracteres")
        String username,

        @NotBlank(message = "La contraseña no puede estar vacía")
        @Size(min = 6, max = 100, message = "La contraseña debe tener entre 6 y 100 caracteres")
        String password
    ) {}

    public record PerfilRequest(
        @Size(max = 100) String nombre,
        @Size(max = 100) String apellido,
        @Size(max = 30)
        @Pattern(regexp = "^[0-9+()\\-\\s]{6,30}$|^$",
                 message = "El teléfono solo puede contener números y los símbolos + - ( )")
        String telefono
    ) {}

    public record CambioPasswordRequest(
        @NotBlank(message = "La contraseña actual es obligatoria")
        String actual,

        @NotBlank(message = "La nueva contraseña es obligatoria")
        @Size(min = 6, max = 100, message = "La nueva contraseña debe tener entre 6 y 100 caracteres")
        String nueva
    ) {}
}
