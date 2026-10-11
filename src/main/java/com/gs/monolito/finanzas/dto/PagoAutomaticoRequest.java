package com.gs.monolito.finanzas.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Pago detectado por el bot de WhatsApp a partir de un comprobante.
 *
 * El bot identifica al receptor (técnico/integrante) por su teléfono y/o
 * nombre, parsea el emisor del pie del mensaje, y manda este request.
 */
@Data
public class PagoAutomaticoRequest {

    /** Id del empleado receptor, si el bot ya lo conoce. Opcional. */
    private Long receptorUsuarioId;

    /** Teléfono del receptor — usado para resolver el empleado si no vino el id. */
    @Pattern(regexp = "^[0-9+()\\-\\s]{6,30}$|^$", message = "El teléfono del receptor tiene un formato inválido")
    private String receptorTelefono;

    /** Nombre del receptor (del pie del mensaje) — resolución por nombre. */
    @Size(max = 150)
    private String receptorNombre;

    @NotNull @Positive(message = "El monto debe ser mayor a cero")
    @Digits(integer = 10, fraction = 2, message = "El monto excede el máximo permitido")
    private BigDecimal monto;

    private LocalDate fecha;

    // ── Trazabilidad del bot ──
    @Size(max = 150)
    private String cargadoPorNombre;
    @Pattern(regexp = "^[0-9+()\\-\\s]{6,30}$|^$", message = "El teléfono tiene un formato inválido")
    private String cargadoPorTelefono;

    @Size(max = 150)
    private String emisor;

    @Size(max = 400)
    private String comprobanteUrl;
    @Size(max = 100)
    private String grupoOrigen;
    @Size(max = 300)
    private String nota;

    @Size(max = 60)
    private String idOperacion;

    // ── Señales para el semáforo (qué tan confiable es lo que el bot leyó). Opcionales: lo que no
    //    se informa cuenta como "no se pudo verificar" y manda el pago a revisión. ──
    /** De dónde salió el monto: COMPROBANTE_ALTA, COMPROBANTE_MEDIA, COMPROBANTE_BAJA o MANUAL. */
    @Size(max = 30)
    private String montoOrigen;
    /** Fecha que figura en el comprobante. */
    private LocalDate fechaComprobante;
    /** ¿Los nombres del mensaje coinciden con los del comprobante? null = el comprobante no traía De/Para. */
    private Boolean nombresCoinciden;
    @Size(max = 300)
    private String detalleNombres;
    /** Rastros de edición del archivo (programa de edición, PDF modificado...), separados por "; ". */
    @Size(max = 300)
    private String senalesArchivo;

    /** ID del mensaje de WhatsApp del comprobante (clave de idempotencia). Opcional. */
    @Size(max = 128)
    private String idMensaje;
    /** ID del mensaje con el pie "Emisor (Receptor)" si vino aparte de la foto. Opcional. */
    @Size(max = 128)
    private String idMensajePie;

    // ── Archivo del comprobante (para guardarlo en MinIO) ──
    private String comprobanteBase64;
    @Size(max = 100)
    private String comprobanteMime;
    @Size(max = 255)
    private String comprobanteNombre;
}
