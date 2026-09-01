package com.gs.monolito.pedidos.service;

import com.gs.monolito.pedidos.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Valida documentos y escaneos subidos a un pedido contra un allowlist de
 * extensiones y, cuando el formato lo permite, sus magic bytes — antes solo
 * se chequeaba que el archivo no estuviera vacío, así que cualquier tipo de
 * archivo (un ejecutable, un HTML/SVG con script embebido, etc.) se guardaba
 * en MinIO y se servía de vuelta tal cual desde /archivo.
 */
@Component
public class UploadValidator {

    private static final Set<String> EXT_DOCUMENTOS = Set.of("pdf", "jpg", "jpeg", "png", "gif", "webp");
    private static final Set<String> EXT_ESCANEOS   = Set.of("stl", "obj", "ply");

    public void validarDocumento(MultipartFile file) {
        String ext = extensionDe(file);
        if (!EXT_DOCUMENTOS.contains(ext)) {
            throw new BusinessException("Tipo de archivo no permitido. Formatos aceptados: PDF, JPG, PNG, GIF, WEBP.");
        }
        byte[] header = leerHeader(file);
        rechazarSiEsEjecutable(header);
        if (!magicBytesOk(header, ext)) {
            throw new BusinessException("El contenido del archivo no coincide con su extensión (" + ext + ").");
        }
    }

    public void validarEscaneo(MultipartFile file) {
        String ext = extensionDe(file);
        if (!EXT_ESCANEOS.contains(ext)) {
            throw new BusinessException("Tipo de archivo no permitido. Formatos aceptados: STL, OBJ, PLY.");
        }
        byte[] header = leerHeader(file);
        rechazarSiEsEjecutable(header);
        // STL (ASCII arranca con texto libre) y OBJ (texto plano) no tienen una
        // firma binaria confiable — el allowlist de extensión + el chequeo
        // anti-ejecutable de arriba es la defensa real posible para esos dos.
        if (ext.equals("ply") && !magicBytesOk(header, ext)) {
            throw new BusinessException("El contenido del archivo no coincide con su extensión (" + ext + ").");
        }
    }

    private void rechazarSiEsEjecutable(byte[] h) {
        if (h.length >= 2 && h[0] == 'M' && h[1] == 'Z') {
            throw new BusinessException("Archivo rechazado: parece un ejecutable de Windows.");
        }
        if (h.length >= 4 && h[0] == 0x7F && h[1] == 'E' && h[2] == 'L' && h[3] == 'F') {
            throw new BusinessException("Archivo rechazado: parece un ejecutable de Linux.");
        }
        if (h.length >= 2 && h[0] == '#' && h[1] == '!') {
            throw new BusinessException("Archivo rechazado: parece un script.");
        }
    }

    private boolean magicBytesOk(byte[] h, String ext) {
        return switch (ext) {
            case "pdf"  -> coincide(h, 0, "%PDF-".getBytes(StandardCharsets.US_ASCII));
            case "png"  -> coincide(h, 0, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
            case "jpg", "jpeg" -> h.length >= 3
                    && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF;
            case "gif"  -> coincide(h, 0, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                        || coincide(h, 0, "GIF89a".getBytes(StandardCharsets.US_ASCII));
            case "webp" -> coincide(h, 0, "RIFF".getBytes(StandardCharsets.US_ASCII))
                        && coincide(h, 8, "WEBP".getBytes(StandardCharsets.US_ASCII));
            case "ply"  -> coincide(h, 0, "ply".getBytes(StandardCharsets.US_ASCII));
            default -> true;
        };
    }

    private boolean coincide(byte[] header, int offset, byte[] firma) {
        if (header.length < offset + firma.length) return false;
        for (int i = 0; i < firma.length; i++) {
            if (header[offset + i] != firma[i]) return false;
        }
        return true;
    }

    private byte[] leerHeader(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            byte[] buf = new byte[16];
            int leidos = in.readNBytes(buf, 0, buf.length);
            return leidos == buf.length ? buf : Arrays.copyOf(buf, leidos);
        } catch (IOException e) {
            throw new BusinessException("Error al leer el archivo");
        }
    }

    private String extensionDe(MultipartFile file) {
        String nombre = file.getOriginalFilename();
        if (nombre == null || !nombre.contains(".")) {
            throw new BusinessException("El archivo debe tener una extensión.");
        }
        return nombre.substring(nombre.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
