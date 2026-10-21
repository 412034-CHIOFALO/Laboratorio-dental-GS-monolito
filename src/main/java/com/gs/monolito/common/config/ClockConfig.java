package com.gs.monolito.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * El reloj de la aplicación como bean: el código que depende de "qué día es hoy" lo recibe inyectado, así las
 * pruebas pueden fijarlo y viajar en el tiempo (un año de devengo en un milisegundo). Usa la zona horaria por
 * defecto de la JVM, que {@link ZonaHoraria} fija al arrancar.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock reloj() {
        return Clock.systemDefaultZone();
    }
}
