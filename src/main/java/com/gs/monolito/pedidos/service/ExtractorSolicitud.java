package com.gs.monolito.pedidos.service;

import com.gs.monolito.pedidos.model.Prioridad;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Saca de un mail lo que se pueda para adelantar el formulario del pedido: paciente, trabajo del catálogo, fecha de
 * entrega, urgencia y precio. Es por reglas, sin IA: lo que no encuentra queda vacío, y lo que encuentra es solo una
 * <b>sugerencia</b>: una persona lo confirma antes de que exista el pedido. Por eso prefiere no devolver nada antes que
 * devolver algo dudoso.
 */
@Component
public class ExtractorSolicitud {

    /** Un trabajo del catálogo contra el que se compara el texto. */
    public record ItemCatalogo(Long id, String nombre) {}

    public record Sugerencia(String paciente, String trabajo, Long catalogoTrabajoId, LocalDate fechaEntrega,
                             Prioridad prioridad, BigDecimal precio, String observaciones) {}

    private static final int MAX_OBSERVACIONES = 900;

    private static final Pattern PACIENTE = Pattern.compile(
            "(?iu)\\b(?:paciente|pac\\.?|px)(?![\\p{L}])\\s*(?:es|:|-|–)?\\s*([\\p{L}][\\p{L} '.\\-]{2,59})");
    private static final Pattern CORTA_NOMBRE = Pattern.compile(
            "(?iu)\\b(?:para|color|pieza|urgente|entrega|trabajo|necesito|necesitamos|por favor|gracias|saludos|con|dni|obra)\\b");
    /** dd/mm[/aaaa] precedido de una palabra que indica fecha de entrega: así "3/4 de corona" no se lee como 3 de abril. */
    private static final Pattern FECHA_EXPLICITA = Pattern.compile(
            "\\b(?:para|entrega|entregar|antes del|hasta el|fecha|dia)\\b\\D{0,12}?(\\d{1,2})\\s*([/\\-])\\s*(\\d{1,2})(?:\\s*\\2\\s*(\\d{2,4}))?(?!\\d)");
    /** Un punto tras una palabra de 3 letras o más cierra la oración; en "M. Pérez" o "Dr. Gómez" el punto es de una abreviatura. */
    private static final Pattern FIN_DE_ORACION = Pattern.compile("(?<=\\p{L}{3})\\.(?:\\s|$)");
    private static final Pattern FECHA_PUNTOS = Pattern.compile("(?<!\\d)(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})(?!\\d)");
    private static final Pattern DIA_SEMANA = Pattern.compile(
            "\\b(?:para|antes del|hasta el|entrega|entregar|necesito|necesitamos)\\b[^\\n.]{0,25}?\\b(lunes|martes|miercoles|jueves|viernes|sabado|domingo)\\b");
    private static final Pattern MANANA = Pattern.compile("\\bpara\\s+(pasado\\s+)?manana\\b");
    private static final Pattern PRECIO = Pattern.compile("\\$\\s*([0-9]{1,3}(?:[.,][0-9]{3})+|[0-9]+)(?:[.,][0-9]{1,2})?");
    private static final Pattern RESPUESTA_CITADA = Pattern.compile(
            "(?imu)^(?:el .{5,120} escribi[oó]:|on .{5,120} wrote:|-{3,}\\s*(?:original message|mensaje original).*)$");

    public Sugerencia extraer(String asunto, String cuerpo, LocalDate hoy, List<ItemCatalogo> catalogo) {
        String limpio = limpiarCuerpo(cuerpo);
        String texto = (asunto == null ? "" : asunto) + "\n" + limpio;
        String normalizado = normalizar(texto);

        ItemCatalogo trabajo = trabajoDelCatalogo(normalizado, catalogo);
        return new Sugerencia(
                paciente(texto),
                trabajo == null ? null : trabajo.nombre(),
                trabajo == null ? null : trabajo.id(),
                fechaEntrega(normalizado, hoy),
                esUrgente(normalizado) ? Prioridad.URGENTE : Prioridad.NORMAL,
                precio(texto),
                observaciones(limpio));
    }

    // ── Cuerpo ───────────────────────────────────────────────────────

    /** Sin las líneas citadas de una respuesta (">") ni lo que viene después de "El ... escribió:". */
    static String limpiarCuerpo(String cuerpo) {
        if (cuerpo == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String linea : cuerpo.replace("\r", "").split("\n")) {
            if (RESPUESTA_CITADA.matcher(linea.strip()).matches()) break;
            if (linea.stripLeading().startsWith(">")) continue;
            sb.append(linea).append('\n');
        }
        return sb.toString().strip();
    }

    private static String observaciones(String limpio) {
        String compacto = limpio.replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").strip();
        if (compacto.isEmpty()) return null;
        return compacto.length() <= MAX_OBSERVACIONES ? compacto : compacto.substring(0, MAX_OBSERVACIONES - 1) + "…";
    }

    // ── Paciente ─────────────────────────────────────────────────────

    private static String paciente(String texto) {
        Matcher m = PACIENTE.matcher(texto);
        while (m.find()) {
            String nombre = CORTA_NOMBRE.split(m.group(1), 2)[0];
            Matcher fin = FIN_DE_ORACION.matcher(nombre);
            if (fin.find()) nombre = nombre.substring(0, fin.start());   // "María González. La necesito..." -> "María González"
            nombre = nombre.replaceAll("[ .'\\-]+$", "").strip();
            if (nombre.length() >= 3 && nombre.contains(" ") || nombre.length() >= 4) {
                return nombre.length() > 150 ? nombre.substring(0, 150) : nombre;
            }
        }
        return null;
    }

    // ── Trabajo ──────────────────────────────────────────────────────

    /** El trabajo del catálogo cuyo nombre aparece en el texto; si hay varios, el de nombre más largo (el más específico). */
    private static ItemCatalogo trabajoDelCatalogo(String normalizado, List<ItemCatalogo> catalogo) {
        if (catalogo == null) return null;
        ItemCatalogo mejor = null;
        int largoMejor = 0;
        for (ItemCatalogo item : catalogo) {
            String nombre = normalizar(item.nombre()).strip();
            if (nombre.length() < 3 || nombre.length() <= largoMejor) continue;
            if (Pattern.compile("(?<![a-z0-9])" + Pattern.quote(nombre) + "(?:s|es)?(?![a-z0-9])").matcher(normalizado).find()) {
                mejor = item;
                largoMejor = nombre.length();
            }
        }
        return mejor;
    }

    // ── Fecha de entrega ─────────────────────────────────────────────

    private static LocalDate fechaEntrega(String normalizado, LocalDate hoy) {
        Matcher m = FECHA_PUNTOS.matcher(normalizado);
        if (m.find()) {
            LocalDate f = fecha(m.group(1), m.group(2), m.group(3), hoy);
            if (f != null) return f;
        }
        m = FECHA_EXPLICITA.matcher(normalizado);
        while (m.find()) {
            LocalDate f = fecha(m.group(1), m.group(3), m.group(4), hoy);
            if (f != null) return f;
        }
        m = DIA_SEMANA.matcher(normalizado);
        if (m.find()) {
            DayOfWeek dia = switch (m.group(1)) {
                case "lunes" -> DayOfWeek.MONDAY;
                case "martes" -> DayOfWeek.TUESDAY;
                case "miercoles" -> DayOfWeek.WEDNESDAY;
                case "jueves" -> DayOfWeek.THURSDAY;
                case "viernes" -> DayOfWeek.FRIDAY;
                case "sabado" -> DayOfWeek.SATURDAY;
                default -> DayOfWeek.SUNDAY;
            };
            int falta = (dia.getValue() - hoy.getDayOfWeek().getValue() + 7) % 7;
            return hoy.plusDays(falta == 0 ? 7 : falta);   // "para el viernes" dicho un viernes es el viernes que viene
        }
        m = MANANA.matcher(normalizado);
        if (m.find()) return hoy.plusDays(m.group(1) == null ? 1 : 2);
        return null;
    }

    /** dd/mm[/aaaa]; sin año, el próximo que venga. Solo devuelve fechas FUTURAS (el pedido exige fecha de entrega futura). */
    private static LocalDate fecha(String dia, String mes, String anio, LocalDate hoy) {
        try {
            int d = Integer.parseInt(dia);
            int mo = Integer.parseInt(mes);
            LocalDate f;
            if (anio == null) {
                f = LocalDate.of(hoy.getYear(), mo, d);
                if (!f.isAfter(hoy)) f = f.plusYears(1);
            } else {
                int a = Integer.parseInt(anio);
                f = LocalDate.of(a < 100 ? 2000 + a : a, mo, d);
            }
            return f.isAfter(hoy) && f.isBefore(hoy.plusYears(2)) ? f : null;
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }

    // ── Urgencia y precio ────────────────────────────────────────────

    private static boolean esUrgente(String normalizado) {
        return normalizado.contains("urgente") || normalizado.contains("urgencia") || normalizado.contains("lo antes posible");
    }

    private static BigDecimal precio(String texto) {
        Matcher m = PRECIO.matcher(texto);
        if (!m.find()) return null;
        String numero = m.group(1);
        // "35.000" o "35,000" son miles; "35000" es 35000; los centavos que sigan ("35.000,50") se ignoran
        BigDecimal valor = new BigDecimal(numero.replaceAll("[.,]", ""));
        return valor.signum() > 0 && valor.compareTo(new BigDecimal("100000000")) < 0 ? valor : null;
    }

    // ── Texto ────────────────────────────────────────────────────────

    /** Minúsculas y sin tildes, para comparar sin que importe cómo lo escribió quien mandó el mail. */
    static String normalizar(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT);
    }
}
