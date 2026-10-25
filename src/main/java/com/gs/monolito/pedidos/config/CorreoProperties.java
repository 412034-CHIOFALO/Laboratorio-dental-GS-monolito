package com.gs.monolito.pedidos.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Configuración de la casilla de correo de pedidos ({@code gs.correo.*}, ver application.properties y
 * PRODUCCION.md). Mientras {@link #habilitado} sea false el sistema no se conecta a ningún correo.
 */
@Component
@ConfigurationProperties(prefix = "gs.correo")
@Getter
@Setter
public class CorreoProperties {

    /** Interruptor general: sin esto no se lee ni se envía ningún mail. */
    private boolean habilitado = false;

    private String host = "";
    private int puerto = 993;
    /** true = IMAPS (lo normal, puerto 993); false solo para pruebas locales. */
    private boolean ssl = true;
    private String usuario = "";
    /** Para Gmail, una contraseña de aplicación (no la contraseña de la cuenta). */
    private String clave = "";
    private String carpeta = "INBOX";
    private int intervaloSegundos = 120;

    /** Tope por archivo adjunto, en MB (Gmail ya limita a 25). */
    private int maxAdjuntoMb = 30;
    private int maxAdjuntos = 20;
    /** Mails de este tamaño máximo en caracteres de cuerpo que se guardan. */
    private int maxCuerpo = 20000;
    /** Intentos fallidos con un mismo mail antes de darlo por leído para que no trabe a los demás. */
    private int maxFallos = 5;

    /** Acuse de recibo al odontólogo (solo si el remitente es un odontólogo conocido). */
    private boolean confirmar = false;
    private String smtpHost = "";
    private int smtpPuerto = 587;
    private String smtpUsuario = "";
    private String smtpClave = "";
    private String remitenteVisible = "";

    /** Expresiones (regex) de remitentes automáticos que se ignoran sin guardar. Se suman a las de siempre. */
    private List<String> remitentesIgnorados = List.of();
    /** Cuántos mails sin autorizar se guardan por remitente en {@link #diasContadorCuarentena} días; el resto se ignora. */
    private int maxCuarentenaPorRemitente = 3;
    private int diasContadorCuarentena = 7;
    /** Los mails sin autorizar que nadie resolvió se borran a los N días. */
    private int diasCuarentena = 30;
    /** Largo máximo del cuerpo que se guarda de un mail sin autorizar (solo hace falta para reconocer de qué se trata). */
    private int maxCuerpoCuarentena = 5000;
    /** Tope de mails sin autorizar esperando a la vez: pasado esto se ignoran (nadie puede llenar la base con spam). */
    private int maxCuarentenaTotal = 200;
    /**
     * Nombre del servidor que escribe {@code Authentication-Results} (Gmail: mx.google.com). Solo se confía en la
     * cabecera con ese nombre; vacío = se toma la primera que haya.
     */
    private String verificador = "mx.google.com";
    /**
     * true = un remitente conocido solo entra directo si el servidor de correo confirma que el mail es auténtico
     * (SPF/DKIM/DMARC); si no puede confirmarlo, queda sin autorizar. Poner false solo con un servidor de pruebas.
     */
    private boolean exigirVerificacion = true;
}
