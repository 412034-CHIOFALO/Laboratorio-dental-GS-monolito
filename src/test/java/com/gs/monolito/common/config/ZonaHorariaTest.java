package com.gs.monolito.common.config;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** La app no puede depender de la zona del contenedor (UTC): el default es Argentina. */
class ZonaHorariaTest {

    @Test
    void sinConfigurar_usaArgentina() {
        assertThat(ZonaHoraria.resolver(null)).isEqualTo(ZoneId.of("America/Argentina/Buenos_Aires"));
        assertThat(ZonaHoraria.resolver("  ")).isEqualTo(ZoneId.of("America/Argentina/Buenos_Aires"));
    }

    @Test
    void configurada_respetaLaZonaDeOtroPais() {
        assertThat(ZonaHoraria.resolver("America/Montevideo")).isEqualTo(ZoneId.of("America/Montevideo"));
    }

    @Test
    void unaZonaInvalida_noRompeElArranque_caeAlDefault() {
        assertThat(ZonaHoraria.resolver("Marte/Olympus")).isEqualTo(ZoneId.of("America/Argentina/Buenos_Aires"));
    }
}
