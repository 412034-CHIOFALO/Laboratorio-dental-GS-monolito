package com.gs.monolito.finanzas.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tarea programada que archiva el reporte financiero del mes que acaba de cerrar.
 * Se ejecuta el día 1 de cada mes a las 06:00 y genera el reporte del mes anterior.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ReporteMensualScheduler {

    private final ReporteMensualService service;

    /** Día 1 de cada mes a las 06:00 → genera y archiva el reporte del mes anterior. */
    @Scheduled(cron = "0 0 6 1 * *")
    public void generarReporteMensual() {
        log.info("[REPORTE] Generación automática del reporte mensual iniciada.");
        try {
            service.generarMesAnterior();
        } catch (Exception e) {
            log.warn("[REPORTE] Falló la generación automática del reporte mensual: {}", e.getMessage());
        }
    }
}
