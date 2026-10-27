package com.gs.monolito.finanzas.service;

import com.gs.monolito.finanzas.dto.PagoAutomaticoRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Semáforo de comprobantes: decide si una transferencia que llega por el bot se registra sola o queda
 * pendiente de que una persona la revise.
 *
 * <p>Qué NO hace: probar que un comprobante es verdadero. Eso solo lo sabe el banco del receptor. Lo que
 * hace es dejar pasar solas únicamente las transferencias "limpias" (todo se leyó con confianza, los
 * nombres coinciden, la fecha es reciente, el archivo no tiene rastros de edición y el monto no es alto) y
 * mandar a revisión todo lo demás. Una imagen falsificada con cuidado puede pasar el semáforo: por eso hay
 * un tope de monto y, más adelante, la confirmación del propio receptor.</p>
 *
 * <p>Lo que el bot no informa cuenta como "no se pudo verificar" y también manda a revisión.</p>
 */
@Component
public class RevisionPago {

    private final boolean activa;
    private final BigDecimal montoMaximo;
    private final int diasMaximos;
    private final Clock reloj;

    @Autowired
    public RevisionPago(@Value("${gs.bot.revision.activa:true}") boolean activa,
                        @Value("${gs.bot.revision.monto-maximo:100000}") BigDecimal montoMaximo,
                        @Value("${gs.bot.revision.dias-maximos:7}") int diasMaximos) {
        this(activa, montoMaximo, diasMaximos, Clock.systemDefaultZone());
    }

    RevisionPago(boolean activa, BigDecimal montoMaximo, int diasMaximos, Clock reloj) {
        this.activa = activa;
        this.montoMaximo = montoMaximo;
        this.diasMaximos = diasMaximos;
        this.reloj = reloj;
    }

    /** Motivos por los que la transferencia necesita revisión; vacío = se puede registrar sola. */
    public List<String> motivos(PagoAutomaticoRequest req) {
        List<String> m = new ArrayList<>();
        if (!activa) return m;

        if (req.getSenalesArchivo() != null && !req.getSenalesArchivo().isBlank()) {
            m.add("Rastros de edición en el archivo: " + req.getSenalesArchivo().trim());
        }

        if (req.getNombresCoinciden() == null) {
            m.add("El comprobante no muestra quién paga y quién cobra: no se pudieron comparar los nombres");
        } else if (!req.getNombresCoinciden()) {
            String detalle = req.getDetalleNombres() != null && !req.getDetalleNombres().isBlank()
                    ? " (" + req.getDetalleNombres().trim() + ")" : "";
            m.add("Los nombres del comprobante no coinciden con los del mensaje" + detalle);
        }

        if (req.getIdOperacion() == null || req.getIdOperacion().isBlank()) {
            m.add("No se pudo leer el N° de operación");
        }

        String origen = req.getMontoOrigen() == null ? "" : req.getMontoOrigen().trim().toUpperCase(Locale.ROOT);
        switch (origen) {
            case "COMPROBANTE_ALTA" -> { /* el único origen que se da por bueno */ }
            case "MANUAL" -> m.add("El monto lo escribió una persona: no se leyó del comprobante");
            case "COMPROBANTE_MEDIA", "COMPROBANTE_BAJA" -> m.add("El monto se leyó del comprobante con poca confianza");
            default -> m.add("No se informó de dónde salió el monto");
        }

        LocalDate hoy = LocalDate.now(reloj);
        LocalDate fecha = req.getFechaComprobante();
        if (fecha == null) {
            m.add("No se pudo leer la fecha del comprobante");
        } else if (fecha.isAfter(hoy.plusDays(1))) {
            m.add("La fecha del comprobante es futura (" + fecha + ")");
        } else if (ChronoUnit.DAYS.between(fecha, hoy) > diasMaximos) {
            m.add("El comprobante tiene " + ChronoUnit.DAYS.between(fecha, hoy) + " días de antigüedad (se acepta hasta "
                    + diasMaximos + ")");
        }

        if (req.getMonto() != null && montoMaximo != null && req.getMonto().compareTo(montoMaximo) >= 0) {
            m.add("Monto alto: " + pesos(req.getMonto()) + " (desde " + pesos(montoMaximo) + " siempre se revisa)");
        }

        // El detalle no es un motivo en sí: explica los de arriba (por qué el bot dudó de lo que leyó).
        if (!m.isEmpty() && req.getDetalleLectura() != null && !req.getDetalleLectura().isBlank()) {
            m.add("Cómo se leyó el comprobante: " + req.getDetalleLectura().trim());
        }
        return m;
    }

    private static String pesos(BigDecimal v) {
        return NumberFormat.getCurrencyInstance(new Locale("es", "AR")).format(v);
    }
}
