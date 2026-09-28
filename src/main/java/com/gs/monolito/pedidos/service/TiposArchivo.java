package com.gs.monolito.pedidos.service;

import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Decide con qué Content-Type y Content-Disposition se sirve un archivo subido
 * a un pedido, a partir SOLO de su extensión (la misma que ya validó
 * {@link UploadValidator} contra un allowlist y magic bytes).
 *
 * <p>Antes se devolvía el Content-Type que mandaba el navegador al subir, que
 * lo controla quien sube: un PNG válido declarado como {@code text/html} se
 * servía como página del mismo origen — XSS guardado que corría con la sesión
 * de quien lo abriera (por ejemplo, el ADMIN). Ahora lo que no es PDF o imagen
 * conocida sale como {@code application/octet-stream} y como descarga, nunca
 * renderizado por el navegador.</p>
 */
public final class TiposArchivo {

    private static final Map<String, MediaType> VISIBLES_EN_NAVEGADOR = Map.of(
        "pdf",  MediaType.APPLICATION_PDF,
        "jpg",  MediaType.IMAGE_JPEG,
        "jpeg", MediaType.IMAGE_JPEG,
        "png",  MediaType.IMAGE_PNG,
        "gif",  MediaType.IMAGE_GIF,
        "webp", MediaType.parseMediaType("image/webp")
    );

    private TiposArchivo() {}

    /** Content-Type según la extensión; cualquier otra cosa, octet-stream. */
    public static MediaType mediaTypePara(String nombreArchivo) {
        return VISIBLES_EN_NAVEGADOR.getOrDefault(extension(nombreArchivo), MediaType.APPLICATION_OCTET_STREAM);
    }

    /**
     * "inline" solo para PDF/imágenes; el resto (escaneos 3D incluidos) como
     * descarga. El nombre va codificado (RFC 5987): comillas o saltos de línea
     * en el nombre original ya no pueden romper el header.
     */
    public static String contentDisposition(String nombreArchivo) {
        String nombre = (nombreArchivo == null || nombreArchivo.isBlank()) ? "archivo" : nombreArchivo;
        ContentDisposition.Builder builder = VISIBLES_EN_NAVEGADOR.containsKey(extension(nombre))
            ? ContentDisposition.inline()
            : ContentDisposition.attachment();
        return builder.filename(nombre, StandardCharsets.UTF_8).build().toString();
    }

    private static String extension(String nombreArchivo) {
        if (nombreArchivo == null) return "";
        int punto = nombreArchivo.lastIndexOf('.');
        return punto < 0 ? "" : nombreArchivo.substring(punto + 1).toLowerCase(Locale.ROOT);
    }
}
