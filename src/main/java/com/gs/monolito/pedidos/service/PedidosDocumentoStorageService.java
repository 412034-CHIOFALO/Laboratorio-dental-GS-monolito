package com.gs.monolito.pedidos.service;

import io.minio.*;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Almacenamiento de documentos/escaneos de pedidos en MinIO. Renombrado desde
 * {@code MinioStorageService} (y su property key de bucket de
 * {@code gs.minio.bucket} a {@code gs.minio.bucket.pedidos}) porque el módulo
 * finanzas tiene su propio servicio equivalente
 * ({@link com.gs.monolito.finanzas.service.FinanzasComprobanteStorageService}) —
 * mismo nombre de clase y misma property key en los microservicios
 * originales, lo que colisionaría al fusionarse en un solo Spring context.
 */
@Service
public class PedidosDocumentoStorageService {

    private static final Logger log = LoggerFactory.getLogger(PedidosDocumentoStorageService.class);

    @Value("${gs.minio.endpoint}")              private String endpoint;
    @Value("${gs.minio.access-key}")            private String accessKey;
    @Value("${gs.minio.secret-key}")            private String secretKey;
    @Value("${gs.minio.bucket.pedidos}")        private String bucket;
    @Value("${gs.minio.enabled:true}")          private boolean enabled;

    private MinioClient client;

    @PostConstruct
    void init() {
        if (!enabled) {
            log.info("[MINIO-DOCS] Deshabilitado por config.");
            return;
        }
        try {
            client = MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .build();
            boolean existe = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!existe) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("[MINIO-DOCS] Bucket '{}' creado.", bucket);
            }
            log.info("[MINIO-DOCS] Conectado a {} (bucket '{}').", endpoint, bucket);
        } catch (Exception e) {
            log.warn("[MINIO-DOCS] No se pudo conectar ({}). Los documentos no se guardarán.", e.getMessage());
            client = null;
        }
    }

    public String subir(byte[] datos, String mime, String nombreOriginal, Long pedidoId) {
        return subir(datos, mime, nombreOriginal, pedidoId, "pedidos");
    }

    public String subir(byte[] datos, String mime, String nombreOriginal, Long pedidoId, String prefijo) {
        if (client == null || datos == null || datos.length == 0) return null;
        return subirStream(new ByteArrayInputStream(datos), datos.length, mime, nombreOriginal, pedidoId, prefijo);
    }

    /**
     * Sube directo desde el InputStream del multipart request, sin volcarlo antes a un
     * byte[] intermedio. Para archivos grandes (STL/OBJ de varios MB) evita duplicar el
     * archivo entero en memoria.
     */
    public String subirStream(InputStream datos, long tamanio, String mime, String nombreOriginal, Long pedidoId, String prefijo) {
        if (client == null || datos == null || tamanio <= 0) return null;
        try {
            LocalDate hoy = LocalDate.now();
            String objectName = String.format("%s/%d/%d/%02d/%s%s",
                    prefijo, pedidoId, hoy.getYear(), hoy.getMonthValue(),
                    UUID.randomUUID(), extensionDe(mime, nombreOriginal));

            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(datos, tamanio, -1)
                    .contentType(mime != null && !mime.isBlank() ? mime : "application/octet-stream")
                    .build());

            log.info("[MINIO-DOCS] Archivo guardado: {}", objectName);
            return objectName;
        } catch (Exception e) {
            log.warn("[MINIO-DOCS] Error subiendo archivo: {}", e.getMessage());
            return null;
        }
    }

    public InputStream descargar(String objectName) {
        if (client == null || objectName == null || objectName.isBlank()) return null;
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectName).build());
        } catch (Exception e) {
            log.warn("[MINIO-DOCS] Error descargando archivo: {}", e.getMessage());
            return null;
        }
    }

    public void eliminar(String objectName) {
        if (client == null || objectName == null || objectName.isBlank()) return;
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectName).build());
            log.info("[MINIO-DOCS] Documento eliminado: {}", objectName);
        } catch (Exception e) {
            log.warn("[MINIO-DOCS] Error eliminando documento: {}", e.getMessage());
        }
    }

    private String extensionDe(String mime, String nombre) {
        if (mime != null) {
            if (mime.contains("pdf"))                         return ".pdf";
            if (mime.contains("png"))                         return ".png";
            if (mime.contains("jpeg") || mime.contains("jpg")) return ".jpg";
            if (mime.contains("gif"))                         return ".gif";
            if (mime.contains("webp"))                        return ".webp";
        }
        if (nombre != null && nombre.contains(".")) {
            return nombre.substring(nombre.lastIndexOf('.'));
        }
        return "";
    }
}
