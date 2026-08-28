package com.gs.monolito.auth.config;

import com.gs.monolito.auth.model.Rol;
import com.gs.monolito.auth.model.Usuario;
import com.gs.monolito.auth.repository.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Crea usuarios de prueba adicionales SOLO en el perfil "dev". Se ejecuta
 * después de {@link AuthDataInitializer} (@Order(2)).
 *
 * Usuarios de prueba: dr_garcia / dra_sanchez (ODONTOLOGO), recepcion (ADMINISTRATIVO),
 * todos con password "dev1234". Estos IDs los usarán los seeds dev de
 * pedidos/finanzas cuando se porten (Etapas 4-5).
 */
@Component
@Profile("dev")
@Order(2)
public class AuthDevDataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthDevDataInitializer.class);
    private static final String DEV_PASSWORD = "dev1234";

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${GS_ADMIN_PASSWORD:admin123}")
    private String adminPassword;

    @Value("${GS_TECNICO_PASSWORD:tecnico123}")
    private String tecnicoPassword;

    public AuthDevDataInitializer(UsuarioRepository usuarioRepository,
                              PasswordEncoder passwordEncoder) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        crearSiNoExiste("dr_garcia",   "Martín", "García",  Rol.ODONTOLOGO);
        crearSiNoExiste("dra_sanchez", "Laura",  "Sánchez", Rol.ODONTOLOGO);
        crearSiNoExiste("recepcion",   "Valentina", "Torres", Rol.ADMINISTRATIVO);

        log.info("");
        log.info("╔════════════════════════════════════════════════════════════════╗");
        log.info("║  [GS-DEV] Usuarios de prueba disponibles                       ║");
        log.info("╠════════════════════════════════════════════════════════════════╣");
        log.info("║  admin        / {}                       (ADMIN)             ║", padRight(adminPassword, 12));
        log.info("║  tecnico1     / {}                       (TECNICO)           ║", padRight(tecnicoPassword, 12));
        log.info("║  dr_garcia    / {}                       (ODONTOLOGO)        ║", padRight(DEV_PASSWORD, 12));
        log.info("║  dra_sanchez  / {}                       (ODONTOLOGO)        ║", padRight(DEV_PASSWORD, 12));
        log.info("║  recepcion    / {}                       (ADMINISTRATIVO)    ║", padRight(DEV_PASSWORD, 12));
        log.info("╚════════════════════════════════════════════════════════════════╝");
    }

    private static String padRight(String s, int n) {
        if (s == null) return " ".repeat(n);
        return s.length() >= n ? s : s + " ".repeat(n - s.length());
    }

    private void crearSiNoExiste(String username, String nombre, String apellido, Rol rol) {
        if (!usuarioRepository.existsByUsername(username)) {
            usuarioRepository.save(Usuario.builder()
                .nombre(nombre)
                .apellido(apellido)
                .username(username)
                .password(passwordEncoder.encode(DEV_PASSWORD))
                .rol(rol)
                .enabled(true)
                .pendienteAprobacion(false)
                .build());
            log.info("[GS-DEV] Usuario '{}' ({}) creado.", username, rol);
        }
    }
}
