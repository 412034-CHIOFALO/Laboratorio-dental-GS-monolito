package com.gs.monolito.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fuera de "dev", la app no arranca con secretos vacíos, cortos, con su
 * default público o con el placeholder de .env.example.
 */
class SecretosProduccionValidatorTest {

    private MockEnvironment entornoSeguro() {
        return new MockEnvironment()
                .withProperty("GS_ADMIN_PASSWORD", "admin-real-2026")
                .withProperty("GS_TECNICO_PASSWORD", "tecnico-real-2026")
                .withProperty("GS_BOT_PEDIDOS_PASSWORD", "bot-pedidos-real")
                .withProperty("GS_INTERNAL_API_KEY", "internal-key-real")
                .withProperty("gs.bot.api-key", "bot-key-real-0001")
                .withProperty("gs.auth.keystore.password", "keystore-real-2026")
                .withProperty("spring.datasource.password", "db-password-real")
                .withProperty("gs.minio.enabled", "true")
                .withProperty("gs.minio.secret-key", "minio-secret-real");
    }

    @Test
    void conTodosLosSecretosPropios_arranca() {
        SecretosProduccionValidator validator = new SecretosProduccionValidator(entornoSeguro());

        assertThat(validator.revisar()).isEmpty();
        validator.validar();
    }

    @Test
    void conDefaultsPublicosOPlaceholders_noArrancaYDiceCualesFaltan() {
        MockEnvironment env = entornoSeguro()
                .withProperty("GS_ADMIN_PASSWORD", "admin123")
                .withProperty("gs.bot.api-key", "gs-bot-dev-key-cambiar-en-prod")
                .withProperty("gs.minio.secret-key", "minioadmin")
                .withProperty("GS_INTERNAL_API_KEY", "cambiar-esto");
        SecretosProduccionValidator validator = new SecretosProduccionValidator(env);

        assertThat(validator.revisar()).containsExactlyInAnyOrder(
                "GS_ADMIN_PASSWORD", "GS_BOT_API_KEY", "MINIO_ROOT_PASSWORD", "GS_INTERNAL_API_KEY");
        assertThatThrownBy(validator::validar)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GS_ADMIN_PASSWORD");
    }

    @Test
    void secretosVaciosOCortos_tambienFrenanElArranque() {
        MockEnvironment env = entornoSeguro()
                .withProperty("gs.auth.keystore.password", "")
                .withProperty("spring.datasource.password", "root");

        assertThat(new SecretosProduccionValidator(env).revisar())
                .containsExactlyInAnyOrder("GS_KEYSTORE_PASSWORD", "DB_PASSWORD");
    }

    @Test
    void conMinioApagado_noExigeSuPassword() {
        MockEnvironment env = entornoSeguro()
                .withProperty("gs.minio.enabled", "false")
                .withProperty("gs.minio.secret-key", "minioadmin");

        assertThat(new SecretosProduccionValidator(env).revisar()).isEmpty();
    }
}
