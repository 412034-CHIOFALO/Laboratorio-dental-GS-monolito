package com.gs.monolito.common.config;

import java.time.ZoneId;
import java.util.TimeZone;

/**
 * Zona horaria del laboratorio para toda la JVM.
 *
 * <p>El contenedor de la app corre en UTC (la imagen no trae TZ): entre las
 * 21:00 y las 23:59 de Argentina, todo {@code LocalDate.now()} ya daba el día
 * siguiente — el número de pedido (PED-fecha), fechas de cobro y entrega,
 * cierre diario de caja, atrasos, y el devengo de sueldos corría a las 21:05.
 * Se fija una sola vez al arrancar, antes que Spring, para que también lo
 * respeten los {@code @Scheduled}.</p>
 *
 * <p>Configurable con {@code GS_ZONA_HORARIA} (pensando en laboratorios de
 * otros países); default America/Argentina/Buenos_Aires.</p>
 */
public final class ZonaHoraria {

    public static final String VARIABLE = "GS_ZONA_HORARIA";
    public static final String DEFAULT = "America/Argentina/Buenos_Aires";

    private ZonaHoraria() {}

    /** Resuelve la zona configurada; un valor inválido cae al default en vez de romper el arranque. */
    static ZoneId resolver(String configurada) {
        if (configurada == null || configurada.isBlank()) return ZoneId.of(DEFAULT);
        try {
            return ZoneId.of(configurada.trim());
        } catch (Exception e) {
            System.err.println("[GS] " + VARIABLE + "=" + configurada + " no es una zona válida — se usa " + DEFAULT);
            return ZoneId.of(DEFAULT);
        }
    }

    public static ZoneId aplicar() {
        ZoneId zona = resolver(System.getenv(VARIABLE));
        TimeZone.setDefault(TimeZone.getTimeZone(zona));
        return zona;
    }
}
