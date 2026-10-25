package com.gs.monolito.pedidos.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lee la cabecera {@code Authentication-Results} que el servidor de correo receptor (Gmail) agrega a cada mail y dice si
 * el remitente es quien afirma ser. Es lo que separa a un odontólogo de alguien que escribe "De: dr.perez@..." sin serlo:
 * el campo "De" lo escribe el que manda, esta cabecera la escribe nuestro servidor de correo.
 *
 * <p>Solo se confía en la cabecera que lleva el nombre de nuestro servidor ({@code mx.google.com}): un mail puede traer
 * otras copias falsificadas, pero el servidor receptor siempre agrega la suya arriba de todo.</p>
 */
final class Autenticacion {

    private static final Pattern FIRMA_DOMINIO = Pattern.compile("header\\.[di]=@?([^\\s;()]+)");
    private static final Pattern ENVELOPE = Pattern.compile("smtp\\.mailfrom=([^\\s;()]+)");

    private Autenticacion() {}

    /**
     * @param cabeceras  los valores de {@code Authentication-Results} del mail (puede ser null)
     * @param email      la dirección "De" del mail
     * @param servidor   el nombre del servidor que debe haber escrito la cabecera; vacío = se toma la primera
     * @return true si el remitente quedó verificado (DMARC, o DKIM/SPF alineados con su dominio); false si hay
     *         cabecera pero no lo verifica; null si no hay ninguna cabecera de nuestro servidor
     */
    static Boolean remitenteVerificado(String[] cabeceras, String email, String servidor) {
        String cabecera = propia(cabeceras, servidor);
        if (cabecera == null) return null;
        String dominio = dominioDe(email);
        if (dominio == null) return false;

        String[] partes = cabecera.replaceAll("\\s+", " ").toLowerCase().split(";");
        for (int i = 1; i < partes.length; i++) {   // la parte 0 es el nombre del servidor
            String p = partes[i].strip();
            if (p.startsWith("dmarc=pass")) return true;
            if (p.startsWith("dkim=pass") && alineado(grupo(FIRMA_DOMINIO, p), dominio)) return true;
            if (p.startsWith("spf=pass") && alineado(dominioDe(grupo(ENVELOPE, p)), dominio)) return true;
        }
        return false;
    }

    private static String propia(String[] cabeceras, String servidor) {
        if (cabeceras == null) return null;
        for (String c : cabeceras) {
            if (c == null || c.isBlank()) continue;
            if (servidor == null || servidor.isBlank()) return c;
            String id = c.split(";", 2)[0].strip().toLowerCase();
            if (id.equals(servidor.strip().toLowerCase())) return c;
        }
        return null;
    }

    private static String grupo(Pattern p, String texto) {
        Matcher m = p.matcher(texto);
        return m.find() ? m.group(1) : null;
    }

    private static String dominioDe(String direccion) {
        if (direccion == null) return null;
        int arroba = direccion.lastIndexOf('@');
        String d = arroba >= 0 ? direccion.substring(arroba + 1) : direccion;
        d = d.strip().toLowerCase();
        return d.isEmpty() ? null : d;
    }

    /** Mismo dominio o uno es subdominio del otro (mail.clinica.com firma por clinica.com). */
    private static boolean alineado(String firmante, String remitente) {
        if (firmante == null || remitente == null) return false;
        String f = firmante.strip().toLowerCase();
        return f.equals(remitente) || f.endsWith("." + remitente) || remitente.endsWith("." + f);
    }
}
