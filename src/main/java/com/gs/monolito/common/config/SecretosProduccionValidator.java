package com.gs.monolito.common.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Frena el arranque si algún secreto quedó con su valor por defecto, vacío o
 * con el placeholder de .env.example ("cambiar-esto").
 *
 * <p>Todos esos defaults están escritos en application.properties y el repo es
 * público: si al .env del servidor le faltaba una variable, la app arrancaba
 * igual con una contraseña que cualquiera puede leer en GitHub (admin123,
 * minioadmin, la API key del bot...). Ahora falla al arrancar y dice cuáles
 * faltan — fail-closed.</p>
 *
 * <p>Corre en cualquier perfil salvo "dev": un servidor que se olvidó de
 * SPRING_PROFILES_ACTIVE también queda cubierto (antes el default era "dev",
 * que además cargaba usuarios de prueba con contraseña dev1234).</p>
 */
@Component
@Profile("!dev")
public class SecretosProduccionValidator {

    static final int LARGO_MINIMO = 8;

    private record Secreto(String propiedad, String variable, Set<String> defaultsConocidos) {}

    private static final List<Secreto> SECRETOS = List.of(
        new Secreto("GS_ADMIN_PASSWORD",           "GS_ADMIN_PASSWORD",        Set.of("admin123")),
        new Secreto("GS_TECNICO_PASSWORD",         "GS_TECNICO_PASSWORD",      Set.of("tecnico123")),
        new Secreto("GS_BOT_PEDIDOS_PASSWORD",     "GS_BOT_PEDIDOS_PASSWORD",  Set.of("cambiar-en-produccion")),
        new Secreto("GS_INTERNAL_API_KEY",         "GS_INTERNAL_API_KEY",      Set.of("gs-internal-key-cambiar-en-prod")),
        new Secreto("gs.bot.api-key",              "GS_BOT_API_KEY",           Set.of("gs-bot-dev-key-cambiar-en-prod")),
        new Secreto("gs.auth.keystore.password",   "GS_KEYSTORE_PASSWORD",     Set.of("gs_keystore_2025")),
        new Secreto("spring.datasource.password",  "DB_PASSWORD",              Set.of("gs_app"))
    );

    private static final Secreto MINIO = new Secreto("gs.minio.secret-key", "MINIO_ROOT_PASSWORD", Set.of("minioadmin"));

    private final Environment env;

    public SecretosProduccionValidator(Environment env) {
        this.env = env;
    }

    @PostConstruct
    void validar() {
        List<String> problemas = revisar();
        if (!problemas.isEmpty()) {
            throw new IllegalStateException(
                "[GS-SECURITY] La app no arranca con secretos inseguros. Definí en .env un valor propio "
                + "(mínimo " + LARGO_MINIMO + " caracteres) para: " + String.join(", ", problemas)
                + ". Para desarrollo local usá SPRING_PROFILES_ACTIVE=dev.");
        }
    }

    /** Package-private para los tests: devuelve las variables con problemas, vacía si está todo bien. */
    List<String> revisar() {
        List<Secreto> aRevisar = new ArrayList<>(SECRETOS);
        if (env.getProperty("gs.minio.enabled", Boolean.class, true)) aRevisar.add(MINIO);

        List<String> problemas = new ArrayList<>();
        for (Secreto s : aRevisar) {
            if (esInseguro(env.getProperty(s.propiedad()), s.defaultsConocidos())) {
                problemas.add(s.variable());
            }
        }
        return problemas;
    }

    private static boolean esInseguro(String valor, Set<String> defaultsConocidos) {
        if (valor == null || valor.isBlank()) return true;
        if (valor.length() < LARGO_MINIMO) return true;
        if (defaultsConocidos.contains(valor)) return true;
        // Placeholders de .env.example: "cambiar-esto", "cambiar-en-produccion", etc.
        return valor.toLowerCase(Locale.ROOT).contains("cambiar");
    }
}
