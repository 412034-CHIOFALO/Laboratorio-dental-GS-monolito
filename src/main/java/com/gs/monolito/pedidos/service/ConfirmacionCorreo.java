package com.gs.monolito.pedidos.service;

import com.gs.monolito.pedidos.config.CorreoProperties;
import com.gs.monolito.pedidos.model.SolicitudEntrante;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * Acuse de recibo al odontólogo: "recibimos tu solicitud, un técnico la revisa". Es opcional ({@code gs.correo.confirmar})
 * y solo se manda a un odontólogo reconocido: a un remitente desconocido no se le contesta (evita responderle al spam).
 * Si el envío falla no pasa nada: la solicitud ya quedó cargada.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConfirmacionCorreo {

    private final CorreoProperties props;

    public void enviar(SolicitudEntrante s) {
        if (!props.isConfirmar() || props.getSmtpHost().isBlank() || s.getOdontologoId() == null
                || s.getRemitenteEmail() == null || s.getRemitenteEmail().isBlank()) {
            return;
        }
        try {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(props.getSmtpHost());
            sender.setPort(props.getSmtpPuerto());
            sender.setUsername(props.getSmtpUsuario().isBlank() ? props.getUsuario() : props.getSmtpUsuario());
            sender.setPassword(props.getSmtpClave().isBlank() ? props.getClave() : props.getSmtpClave());
            Properties p = sender.getJavaMailProperties();
            p.put("mail.smtp.auth", "true");
            p.put("mail.smtp.starttls.enable", "true");
            p.put("mail.smtp.connectiontimeout", "15000");
            p.put("mail.smtp.timeout", "30000");

            SimpleMailMessage m = new SimpleMailMessage();
            m.setFrom(props.getRemitenteVisible().isBlank() ? sender.getUsername() : props.getRemitenteVisible());
            m.setTo(s.getRemitenteEmail());
            m.setSubject("Recibimos tu solicitud (#" + s.getId() + ")");
            m.setText("Hola " + (s.getOdontologoNombre() == null ? "" : s.getOdontologoNombre()) + ",\n\n"
                    + "Recibimos tu mail \"" + (s.getAsunto() == null ? "(sin asunto)" : s.getAsunto()) + "\" y quedó registrado como "
                    + "solicitud #" + s.getId() + ". Un técnico del laboratorio la revisa y te confirmamos el pedido.\n\n"
                    + "Si es urgente, comunicate directamente con el laboratorio.\n\nLaboratorio GS");
            sender.send(m);
            log.info("[CORREO] Acuse de recibo enviado a {} (solicitud #{}).", s.getRemitenteEmail(), s.getId());
        } catch (Exception e) {
            log.warn("[CORREO] No se pudo enviar el acuse de recibo a {}: {}", s.getRemitenteEmail(), e.getMessage());
        }
    }
}
