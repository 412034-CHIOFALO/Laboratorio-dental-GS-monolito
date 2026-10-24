package com.gs.monolito.common.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Arma el "qué cambió" de un evento de auditoría: {@code teléfono: …4521 → …9876; clínica: Norte → Sur}.
 *
 * <ul>
 *   <li>{@link #texto} muestra los valores tal cual (nombre, clínica, dirección).</li>
 *   <li>{@link #sensible} los muestra enmascarados (teléfono, email, DNI, CUIT): la bitácora tiene que decir <i>que</i>
 *       cambió el teléfono, y de cuál a cuál alcanza con los últimos cuatro dígitos para reconocerlo.</li>
 *   <li>Un campo que no cambió no aparece. Si no cambió nada, {@link #hayCambios()} da false y no hace falta registrar el evento.</li>
 * </ul>
 *
 * El resumen se corta antes de los 500 caracteres de la columna {@code detalle}: la auditoría nunca tiene que hacer
 * fallar la operación que está registrando.
 */
public final class Cambios {

    /** La columna {@code detalle} de auditoría_eventos es varchar(500). */
    static final int MAX = 480;
    private static final String VACIO = "(vacío)";

    private final List<String> partes = new ArrayList<>();

    private Cambios() {}

    public static Cambios nuevo() {
        return new Cambios();
    }

    /** Campo común: se registra si cambió, con el valor anterior y el nuevo. */
    public Cambios texto(String campo, Object antes, Object despues) {
        return diff(campo, normalizar(antes), normalizar(despues), false);
    }

    /** Dato personal: se registra si cambió, enmascarado. */
    public Cambios sensible(String campo, Object antes, Object despues) {
        return diff(campo, normalizar(antes), normalizar(despues), true);
    }

    /** Para altas: "campo: valor" si hay valor. */
    public Cambios dato(String campo, Object valor) {
        String v = normalizar(valor);
        if (v != null) partes.add(campo + ": " + v);
        return this;
    }

    /** Para altas con un dato personal: "campo: …1234" si hay valor. */
    public Cambios datoSensible(String campo, Object valor) {
        String v = normalizar(valor);
        if (v != null) partes.add(campo + ": " + enmascarar(v));
        return this;
    }

    public boolean hayCambios() {
        return !partes.isEmpty();
    }

    public String resumen() {
        String s = String.join("; ", partes);
        return s.length() <= MAX ? s : s.substring(0, MAX - 1) + "…";
    }

    private Cambios diff(String campo, String antes, String despues, boolean sensible) {
        if (Objects.equals(antes, despues)) return this;
        partes.add(campo + ": " + mostrar(antes, sensible) + " → " + mostrar(despues, sensible));
        return this;
    }

    private static String mostrar(String v, boolean sensible) {
        if (v == null) return VACIO;
        return sensible ? enmascarar(v) : v;
    }

    private static String normalizar(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** Email: {@code j***@clinica.com}. Teléfono, DNI, CUIT y similares: {@code …4521} (últimos cuatro dígitos). */
    static String enmascarar(String v) {
        int arroba = v.indexOf('@');
        if (arroba > 0) return v.charAt(0) + "***" + v.substring(arroba);
        String digitos = v.replaceAll("\\D", "");
        return digitos.length() >= 4 ? "…" + digitos.substring(digitos.length() - 4) : "***";
    }
}
