package com.gs.monolito.finanzas.dto;

import com.gs.monolito.finanzas.model.ReporteMensual;

import java.time.LocalDateTime;

/**
 * Datos de un reporte mensual archivado, para listarlo en la sección Documentos.
 * El PDF se descarga aparte mediante una URL temporal.
 */
public record ReporteMensualResponse(
    Long id,
    int anio,
    int mes,
    String periodo,
    String nombreArchivo,
    LocalDateTime generadoEn,
    boolean automatico
) {
    private static final String[] MESES = {
        "", "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
        "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"
    };

    public static ReporteMensualResponse from(ReporteMensual r) {
        String nombreMes = (r.getMes() >= 1 && r.getMes() <= 12) ? MESES[r.getMes()] : "Mes " + r.getMes();
        return new ReporteMensualResponse(
            r.getId(),
            r.getAnio(),
            r.getMes(),
            nombreMes + " " + r.getAnio(),
            r.getNombreArchivo(),
            r.getGeneradoEn(),
            r.isAutomatico()
        );
    }
}
