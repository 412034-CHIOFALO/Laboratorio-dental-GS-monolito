package com.gs.monolito.pedidos.service;

import com.gs.monolito.pedidos.config.CorreoProperties;
import com.gs.monolito.pedidos.model.SolicitudEntrante;
import com.gs.monolito.pedidos.repository.SolicitudEntranteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Borra los mails sin autorizar que nadie reconoció pasado el plazo ({@code gs.correo.dias-cuarentena}): lo que nadie
 * quiso no se guarda para siempre, y la bandeja de remitentes nuevos no crece sin límite. No depende de que el correo
 * esté habilitado: si se lo apaga, lo que quedó esperando igual vence.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LimpiezaCuarentena {

    private final SolicitudEntranteRepository solicitudRepo;
    private final CorreoProperties props;
    private final Clock reloj;

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public long purgar() {
        LocalDateTime limite = LocalDateTime.now(reloj).minusDays(props.getDiasCuarentena());
        long borradas = solicitudRepo.deleteByEstadoAndFechaRecepcionBefore(SolicitudEntrante.Estado.SIN_AUTORIZAR, limite);
        if (borradas > 0) {
            log.info("[CORREO] Se borraron {} mail(s) sin autorizar con más de {} días.", borradas, props.getDiasCuarentena());
        }
        return borradas;
    }
}
