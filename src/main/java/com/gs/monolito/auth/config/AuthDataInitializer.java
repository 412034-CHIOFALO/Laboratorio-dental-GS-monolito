package com.gs.monolito.auth.config;

import com.gs.monolito.auth.model.Rol;
import com.gs.monolito.auth.model.Usuario;
import com.gs.monolito.auth.repository.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AuthDataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthDataInitializer.class);

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${GS_ADMIN_PASSWORD:admin123}")
    private String adminPassword;

    @Value("${GS_TECNICO_PASSWORD:tecnico123}")
    private String tecnicoPassword;

    @Value("${GS_BOT_PEDIDOS_PASSWORD:cambiar-en-produccion}")
    private String botPedidosPassword;

    public AuthDataInitializer(UsuarioRepository usuarioRepository, PasswordEncoder passwordEncoder) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (!usuarioRepository.existsByUsername("admin")) {
            if ("admin123".equals(adminPassword)) {
                log.warn("[GS-SECURITY] Usando contraseña por defecto para 'admin'. " +
                         "CAMBIAR antes de producción vía GS_ADMIN_PASSWORD.");
            }
            Usuario admin = Usuario.builder()
                .nombre("Rebeca")
                .apellido("González")
                .username("admin")
                .password(passwordEncoder.encode(adminPassword))
                .rol(Rol.ADMIN)
                .enabled(true)
                .pendienteAprobacion(false)
                .build();
            usuarioRepository.save(admin);
            log.info("[GS] Usuario 'admin' creado correctamente.");
        }

        if (!usuarioRepository.existsByUsername("tecnico1")) {
            if ("tecnico123".equals(tecnicoPassword)) {
                log.warn("[GS-SECURITY] Usando contraseña por defecto para 'tecnico1'. " +
                         "CAMBIAR antes de producción vía GS_TECNICO_PASSWORD.");
            }
            Usuario tecnico = Usuario.builder()
                .nombre("Carlos")
                .apellido("López")
                .username("tecnico1")
                .password(passwordEncoder.encode(tecnicoPassword))
                .rol(Rol.TECNICO)
                .enabled(true)
                .pendienteAprobacion(false)
                .build();
            usuarioRepository.save(tecnico);
            log.info("[GS] Usuario 'tecnico1' creado correctamente.");
        }

        if (!usuarioRepository.existsByUsername("bot-pedidos")) {
            if ("cambiar-en-produccion".equals(botPedidosPassword)) {
                log.warn("[GS-SECURITY] Usando contraseña por defecto para 'bot-pedidos'. " +
                         "CAMBIAR antes de producción vía GS_BOT_PEDIDOS_PASSWORD " +
                         "(debe coincidir con BOT_PEDIDOS_PASSWORD del bot).");
            }
            Usuario botPedidos = Usuario.builder()
                .nombre("Bot")
                .apellido("Pedidos")
                .username("bot-pedidos")
                .password(passwordEncoder.encode(botPedidosPassword))
                .rol(Rol.ADMINISTRATIVO)
                .enabled(true)
                .pendienteAprobacion(false)
                .build();
            usuarioRepository.save(botPedidos);
            log.info("[GS] Usuario de servicio 'bot-pedidos' creado correctamente.");
        }
    }
}
