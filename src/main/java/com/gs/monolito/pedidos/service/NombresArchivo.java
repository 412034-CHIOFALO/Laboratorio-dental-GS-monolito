package com.gs.monolito.pedidos.service;

import java.util.Locale;

/** Limpieza de nombres de archivo que vienen de afuera (el navegador o un mail). */
public final class NombresArchivo {

    private NombresArchivo() {}

    /**
     * El nombre tal como llega puede traer una ruta ("C:\\scans\\a.stl", "../x.stl") o caracteres de control: se queda
     * solo con el último tramo, sin controles y con un largo razonable. Nunca devuelve vacío.
     */
    public static String seguro(String original) {
        String n = original == null ? "" : original.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (n.length() > 200) {
            int punto = n.lastIndexOf('.');
            String ext = punto >= 0 && n.length() - punto <= 10 ? n.substring(punto) : "";
            n = n.substring(0, 200 - ext.length()) + ext;
        }
        return n.isEmpty() ? "archivo" : n;
    }

    /** La extensión en minúsculas y sin el punto; vacía si no tiene. */
    public static String extension(String nombre) {
        int punto = nombre.lastIndexOf('.');
        return punto < 0 ? "" : nombre.substring(punto + 1).toLowerCase(Locale.ROOT);
    }
}
