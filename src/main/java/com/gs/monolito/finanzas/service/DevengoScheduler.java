package com.gs.monolito.finanzas.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tarea programada que devenga el sueldo de todos los empleados activos,
 * prorrateado por día corrido según su frecuencia de pago (ver
 * {@link GestionSueldoService#devengarDiario()}). {@code @EnableScheduling}
 * se declara una sola vez en {@link com.gs.monolito.MonolitoApplication}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DevengoScheduler {

    private final IGestionSueldoService service;

    /** Todos los días a las 00:05 → devenga el día que acaba de cerrar. */
    @Scheduled(cron = "0 5 0 * * *")
    public void devengarDiario() {
        log.info("[DEVENGO] Cálculo automático de devengado diario iniciado.");
        try {
            service.devengarDiario();
        } catch (Exception e) {
            log.warn("[DEVENGO] Falló el cálculo automático de devengado: {}", e.getMessage());
        }
    }
}
