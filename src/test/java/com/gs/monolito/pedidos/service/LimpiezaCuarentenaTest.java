package com.gs.monolito.pedidos.service;

import com.gs.monolito.pedidos.config.CorreoProperties;
import com.gs.monolito.pedidos.model.SolicitudEntrante;
import com.gs.monolito.pedidos.repository.SolicitudEntranteRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LimpiezaCuarentenaTest {

    @Test
    void borraSoloLosSinAutorizarMasViejosQueElPlazo() {
        SolicitudEntranteRepository repo = mock(SolicitudEntranteRepository.class);
        CorreoProperties props = new CorreoProperties();
        props.setDiasCuarentena(30);
        Clock reloj = Clock.fixed(Instant.parse("2026-10-21T15:00:00Z"), ZoneId.of("UTC"));
        when(repo.deleteByEstadoAndFechaRecepcionBefore(any(), any())).thenReturn(4L);

        long borradas = new LimpiezaCuarentena(repo, props, reloj).purgar();

        assertThat(borradas).isEqualTo(4);
        ArgumentCaptor<LocalDateTime> limite = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repo).deleteByEstadoAndFechaRecepcionBefore(org.mockito.ArgumentMatchers.eq(SolicitudEntrante.Estado.SIN_AUTORIZAR), limite.capture());
        assertThat(limite.getValue()).isEqualTo(LocalDateTime.of(2026, 9, 21, 15, 0));
    }
}
