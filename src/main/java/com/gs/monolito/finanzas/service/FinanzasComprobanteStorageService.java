package com.gs.monolito.finanzas.service;

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
 * Almacenamiento de comprobantes en MinIO. Renombrado desde
 * {@code MinioStorageService} (y su property key de bucket de
 * {@code gs.minio.bucket} a {@code gs.minio.bucket.finanzas}) porque el
 * módulo pedidos tiene su propio servicio de almacenamiento equivalente
 * (Etapa 5) — mismo nombre de clase y misma property key en el original,
 * lo que colisionaría al fusionarse en un solo Spring context.
 *
 * <p>Si MinIO no está disponible, las operaciones devuelven null y el pago se
 * registra igual (sin archivo) — el almacenamiento es "best effort".</p>
 */
@Service
public class FinanzasComprobanteStorageService {

    private static final Logger log = LoggerFactory.getLogger(FinanzasComprobanteStorageService.class);

    @Value("${gs.minio.endpoint}")              private String endpoint;
    @Value("${gs.minio.access-key}")            private String accessKey;
    @Value("${gs.minio.secret-key}")            private String secretKey;
    @Value("${gs.minio.bucket.finanzas}")       private String bucket;
    @Value("${gs.minio.enabled:true}")          private boolean enabled;

    private MinioClient client;

    @PostConstruct
    void init() {
        if (!enabled) {
            log.info("[MINIO] Deshabilitado por config.");
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
                log.info("[MINIO] Bucket '{}' creado.", bucket);
            }
            log.info("[MINIO] Conectado a {} (bucket '{}').", endpoint, bucket);
        } catch (Exception e) {
            log.warn("[MINIO] No se pudo conectar ({}). Los comprobantes no se guardarán.", e.getMessage());
            client = null;
        }
    }

    /**
     * Sube un comprobante y devuelve el nombre del objeto (path en el bucket),
     * o null si MinIO no está disponible.
     */
    public String subir(byte[] datos, String mime, String nombreOriginal) {
        if (client == null || datos == null || datos.length == 0) return null;
        try {
            LocalDate hoy = LocalDate.now();
            String objectName = String.format("comprobantes/%d/%02d/%s%s",
                    hoy.getYear(), hoy.getMonthValue(), UUID.randomUUID(), extensionDe(mime, nombreOriginal));

            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(new ByteArrayInputStream(datos), datos.length, -1)
                    .contentType(mime != null && !mime.isBlank() ? mime : "application/octet-stream")
                    .build());

            log.info("[MINIO] Comprobante guardado: {}", objectName);
            return objectName;
        } catch (Exception e) {
            log.warn("[MINIO] Error subiendo comprobante: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Sube (o sobrescribe) el PDF de un reporte mensual y devuelve su {@code objectName},
     * o {@code null} si MinIO no está disponible. El path es determinístico por año/mes,
     * de modo que regenerar el reporte reemplaza el objeto anterior.
     */
    public String subirReporte(byte[] datos, int anio, int mes) {
        if (client == null || datos == null || datos.length == 0) return null;
        try {
            String objectName = String.format("reportes/%d/resumen-mensual-%d-%02d.pdf", anio, anio, mes);
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(new ByteArrayInputStream(datos), datos.length, -1)
                    .contentType("application/pdf")
                    .build());
            log.info("[MINIO] Reporte mensual guardado: {}", objectName);
            return objectName;
        } catch (Exception e) {
            log.warn("[MINIO] Error subiendo reporte mensual: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Abre un stream de lectura del objeto para servirlo a través del propio backend
     * (proxy), evitando exponer el endpoint de MinIO directamente al navegador.
     * Quien llama es responsable de cerrar el stream devuelto.
     */
    public InputStream descargar(String objectName) {
        if (client == null || objectName == null || objectName.isBlank()) return null;
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectName).build());
        } catch (Exception e) {
            log.warn("[MINIO] Error descargando archivo: {}", e.getMessage());
            return null;
        }
    }

    private String extensionDe(String mime, String nombre) {
        if (mime != null) {
            if (mime.contains("pdf"))  return ".pdf";
            if (mime.contains("png"))  return ".png";
            if (mime.contains("jpeg") || mime.contains("jpg")) return ".jpg";
        }
        if (nombre != null && nombre.contains(".")) {
            return nombre.substring(nombre.lastIndexOf('.'));
        }
        return "";
    }
}
