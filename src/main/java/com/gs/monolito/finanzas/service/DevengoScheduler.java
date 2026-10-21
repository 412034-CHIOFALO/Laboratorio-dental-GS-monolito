package com.gs.monolito.finanzas.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispara el cierre del devengo de sueldos ({@link DevengoService#devengarTodos()}).
 * {@code @EnableScheduling} se declara una sola vez en {@link com.gs.monolito.MonolitoApplication}.
 *
 * <p>Corre <b>al arrancar</b> y <b>una vez por hora</b>, no solo a medianoche: el servidor no está prendido las
 * 24 horas, y un cálculo que dependía de estar encendido a las 00:05 dejaba los saldos días atrasados (se
 * midió: 11 días). Es idempotente, así que repetirlo no cuesta nada: si ya está al día, no cambia nada.</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DevengoScheduler {

    private final DevengoService devengo;

    /** Apenas la aplicación queda lista: se pone al día de lo que pasó mientras estuvo apagada. */
    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        ejecutar("al arrancar");
    }

    /** Al minuto 5 de cada hora. */
    @Scheduled(cron = "0 5 * * * *")
    public void cadaHora() {
        ejecutar("programado");
    }

    private void ejecutar(String motivo) {
        try {
            int n = devengo.devengarTodos();
            if (n > 0) log.info("[DEVENGO] Cierre de devengo ({}): {} empleado(s) actualizado(s).", motivo, n);
        } catch (Exception e) {
            log.warn("[DEVENGO] Falló el cierre de devengo ({}): {}", motivo, e.getMessage());
        }
    }
}
