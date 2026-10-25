package com.gs.monolito.finanzas.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Compara nombres de personas para decidir "¿este es el mismo?" sin equivocarse de persona.
 *
 * <p>Antes se buscaba cualquier palabra de más de tres letras del emisor dentro del nombre del odontólogo, y se
 * tomaba el primero: un pago de "Juan Pérez" podía saldar la deuda de "Juan Carlos Gómez" (compartían "juan").
 * Acá se compara por palabras COMPLETAS y en conjunto: no importa el orden ("Pérez Juan" = "Juan Pérez"), se ignoran
 * títulos ("Dr.", "Dra.") y conectores ("de", "del"), y una palabra suelta nunca alcanza para decir que son la misma
 * persona.</p>
 */
public final class NombresPersona {

    public enum Parecido {
        /** Mismas palabras (en cualquier orden). */
        EXACTO,
        /** Uno tiene todas las palabras del otro y algunas más ("Juan Pérez" y "Juan Carlos Pérez"), con al menos dos palabras en común. */
        CONTENIDO,
        NINGUNO
    }

    private static final Set<String> TITULOS = Set.of(
            "dr", "dra", "doctor", "doctora", "lic", "licenciado", "licenciada", "sr", "sra", "srta", "senor", "senora",
            "odont", "odontologo", "odontologa", "prof", "ing", "arq", "cdor", "cdora");
    private static final Set<String> CONECTORES = Set.of("de", "del", "la", "las", "los", "y", "e");

    private NombresPersona() {}

    /** Palabras significativas de un nombre: en minúsculas, sin acentos, sin títulos ni conectores. */
    public static Set<String> palabras(String nombre) {
        if (nombre == null) return Set.of();
        String limpio = Normalizer.normalize(nombre, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9ñ]+", " ")
                .trim();
        if (limpio.isEmpty()) return Set.of();
        Set<String> salida = new LinkedHashSet<>();
        for (String p : limpio.split(" ")) {
            if (p.isBlank() || TITULOS.contains(p) || CONECTORES.contains(p)) continue;
            salida.add(p);
        }
        return salida;
    }

    public static Parecido comparar(String a, String b) {
        Set<String> pa = palabras(a);
        Set<String> pb = palabras(b);
        if (pa.isEmpty() || pb.isEmpty()) return Parecido.NINGUNO;
        if (pa.equals(pb)) return Parecido.EXACTO;
        Set<String> chico = pa.size() <= pb.size() ? pa : pb;
        Set<String> grande = chico == pa ? pb : pa;
        if (chico.size() >= 2 && grande.containsAll(chico)) return Parecido.CONTENIDO;
        return Parecido.NINGUNO;
    }

    /**
     * ¿El nombre que figura en un comprobante (quien RECIBE) es este empleado o proveedor? Acepta que el guardado
     * tenga palabras de más (segundo nombre, "(demo)"), pero exige al menos dos palabras del comprobante dentro del
     * guardado, o que sean idénticos.
     */
    public static boolean coincideReceptor(String guardado, String delComprobante) {
        Parecido p = comparar(guardado, delComprobante);
        if (p == Parecido.EXACTO) return true;
        if (p != Parecido.CONTENIDO) return false;
        return palabras(guardado).containsAll(palabras(delComprobante));
    }

    /** Para mostrar: los candidatos como texto ("A, B y C"). */
    public static String enumerar(List<String> nombres) {
        List<String> n = new ArrayList<>(nombres);
        if (n.size() <= 1) return String.join("", n);
        return String.join(", ", n.subList(0, n.size() - 1)) + " o " + n.get(n.size() - 1);
    }
}
