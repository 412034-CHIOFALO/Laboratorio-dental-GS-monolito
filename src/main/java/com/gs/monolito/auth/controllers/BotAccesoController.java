package com.gs.monolito.auth.controllers;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chequeo de acceso al bot de WhatsApp que consulta nginx ({@code auth_request})
 * antes de dejar pasar cualquier request a {@code /api/bot/**}.
 *
 * <p>El bot es un proceso Node aparte que solo conoce la API key compartida, y
 * nginx se la agrega a cada request que le reenvía. Antes de esto, nginx lo
 * hacía sin verificar nada: cualquiera desde internet podía mandar WhatsApps
 * desde el número del laboratorio, desvincular la sesión del bot o
 * re-vincularlo a su propio teléfono y cargar pagos falsos. Ahora nginx
 * primero pregunta acá con la cookie de sesión del usuario: 204 deja pasar,
 * 401/403 corta la request.</p>
 *
 * <p>No tiene lógica propia: la regla (autenticado con ADMIN o ADMINISTRATIVO)
 * vive en {@code AuthSecurityConfig}, y si llega hasta acá es porque la cumplió.</p>
 */
@Hidden
@RestController
public class BotAccesoController {

    public static final String RUTA = "/api/auth/bot-acceso";

    @RequestMapping(RUTA)
    public ResponseEntity<Void> verificar() {
        return ResponseEntity.noContent().build();
    }
}
