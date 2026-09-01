package com.gs.monolito.pedidos.service;

import com.gs.monolito.pedidos.model.Odontologo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * Dispara notificaciones WhatsApp a odontólogos cuando su pedido está listo.
 * El bot sigue siendo un proceso Node.js externo (fuera del monolito), así
 * que esto se queda como una llamada HTTP real, sin cambios.
 *
 * Fire-and-forget: los errores no se propagan — la transición de estado no
 * debe fallar por un problema de notificación.
 */
@Service
@Slf4j
public class NotificacionBotService {

    private final RestClient restClient;

    public NotificacionBotService(
            @Value("${gs.bot.uri:http://localhost:3001}") String botUri,
            @Value("${gs.bot.api-key:}") String apiKey) {
        this.restClient = RestClient.builder()
                .baseUrl(botUri)
                .defaultHeader("X-Bot-Api-Key", apiKey)
                // Sin esto, un bot colgado/caído deja el hilo de la transición de
                // estado del pedido esperando indefinidamente (el default de Spring
                // es sin timeout). Fire-and-forget real: falla rápido y sigue.
                .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(Duration.ofSeconds(3))
                        .withReadTimeout(Duration.ofSeconds(5))))
                .build();
    }

    public void notificarPedidoListo(String nroPedido, String trabajo, Odontologo odontologo) {
        if (odontologo == null || odontologo.getTelefono() == null || odontologo.getTelefono().isBlank()) {
            log.debug("[Bot] Odontólogo sin teléfono — no se envía notificación para {}", nroPedido);
            return;
        }
        try {
            restClient.post()
                    .uri("/api/bot/notificar")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "telefono",  odontologo.getTelefono(),
                            "nombre",    odontologo.getNombre(),
                            "nroPedido", nroPedido,
                            "trabajo",   trabajo
                    ))
                    .retrieve()
                    .toBodilessEntity();
            log.info("[Bot] Notificación WhatsApp enviada a {} ({})", odontologo.getNombre(), nroPedido);
        } catch (Exception e) {
            log.warn("[Bot] No se pudo enviar notificación WhatsApp para {}: {}", nroPedido, e.getMessage());
        }
    }
}
