package com.gs.monolito.pedidos.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.pedidos.config.CorreoProperties;
import com.gs.monolito.pedidos.exception.BusinessException;
import com.gs.monolito.pedidos.model.Odontologo;
import com.gs.monolito.pedidos.model.RemitenteAutorizado;
import com.gs.monolito.pedidos.model.SolicitudEntrante;
import com.gs.monolito.pedidos.model.SolicitudEntrante.RemitenteTipo;
import com.gs.monolito.pedidos.repository.OdontologoRepository;
import com.gs.monolito.pedidos.repository.RemitenteAutorizadoRepository;
import com.gs.monolito.pedidos.repository.SolicitudEntranteRepository;
import com.gs.monolito.pedidos.service.RemitenteService.Clasificacion;
import com.gs.monolito.pedidos.service.RemitenteService.Destino;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A quién se le cree. Que un mail diga venir de un odontólogo no prueba que lo sea: solo entran directo los remitentes
 * que el laboratorio conoce Y cuyo mail el servidor de correo verificó.
 */
class RemitenteServiceTest {

    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-21T15:00:00Z"), ZoneId.of("America/Argentina/Buenos_Aires"));

    private RemitenteAutorizadoRepository remitenteRepo;
    private OdontologoRepository odontologoRepo;
    private SolicitudEntranteRepository solicitudRepo;
    private AuditoriaService auditoria;
    private CorreoProperties props;
    private RemitenteService servicio;

    @BeforeEach
    void preparar() {
        remitenteRepo = mock(RemitenteAutorizadoRepository.class);
        odontologoRepo = mock(OdontologoRepository.class);
        solicitudRepo = mock(SolicitudEntranteRepository.class);
        auditoria = mock(AuditoriaService.class);
        props = new CorreoProperties();
        when(remitenteRepo.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(remitenteRepo.save(any(RemitenteAutorizado.class))).thenAnswer(inv -> {
            RemitenteAutorizado r = inv.getArgument(0);
            if (r.getId() == null) r.setId(1L);
            return r;
        });
        servicio = new RemitenteService(remitenteRepo, odontologoRepo, solicitudRepo, auditoria, props, RELOJ);
    }

    private static Odontologo perez() {
        return Odontologo.builder().id(5L).nombre("Dr. Carlos Pérez").email("perez@clinica.com").activo(true).build();
    }

    private void fichaDePerez() {
        when(odontologoRepo.findByActivoTrueAndEmailIgnoreCase("perez@clinica.com")).thenReturn(List.of(perez()));
        when(odontologoRepo.findById(5L)).thenReturn(Optional.of(perez()));
    }

    // ── Conocidos ────────────────────────────────────────────────────

    @Test
    void elEmailDeLaFichaDeUnOdontologoVerificadoEntraDirecto() {
        fichaDePerez();

        Clasificacion c = servicio.clasificar("Perez@Clinica.com", true);

        assertThat(c.destino()).isEqualTo(Destino.CONOCIDO);
        assertThat(c.tipo()).isEqualTo(RemitenteTipo.ODONTOLOGO);
        assertThat(c.odontologoId()).isEqualTo(5L);
        assertThat(c.odontologoNombre()).isEqualTo("Dr. Carlos Pérez");
        assertThat(c.verificado()).isTrue();
    }

    @Test
    void unaSecretariaAutorizadaEntraDirectoYSaleComoSecretariaDelOdontologo() {
        fichaDePerez();
        RemitenteAutorizado laura = RemitenteAutorizado.builder().id(2L).email("laura@consultorio.com").tipo(RemitenteTipo.SECRETARIA)
                .odontologoId(5L).etiqueta("Secretaria de Dr. Carlos Pérez").estado(RemitenteAutorizado.Estado.AUTORIZADO).build();
        when(remitenteRepo.findByEmailIgnoreCase("laura@consultorio.com")).thenReturn(Optional.of(laura));

        Clasificacion c = servicio.clasificar("laura@consultorio.com", true);

        assertThat(c.destino()).isEqualTo(Destino.CONOCIDO);
        assertThat(c.tipo()).isEqualTo(RemitenteTipo.SECRETARIA);
        assertThat(c.odontologoId()).isEqualTo(5L);
        assertThat(c.etiqueta()).isEqualTo("Secretaria de Dr. Carlos Pérez");
    }

    @Test
    void unaCasillaCompartidaPorVariosOdontologosEsConocidaPeroNoSeAdivinaCual() {
        when(odontologoRepo.findByActivoTrueAndEmailIgnoreCase("clinica@clinica.com")).thenReturn(List.of(
                Odontologo.builder().id(1L).nombre("Dr. A").build(), Odontologo.builder().id(2L).nombre("Dr. B").build()));

        Clasificacion c = servicio.clasificar("clinica@clinica.com", true);

        assertThat(c.destino()).isEqualTo(Destino.CONOCIDO);
        assertThat(c.odontologoId()).isNull();
    }

    // ── Desconocidos y suplantación ──────────────────────────────────

    @Test
    void unDesconocidoQuedaSinAutorizarAunqueElMailSeaAutentico() {
        Clasificacion c = servicio.clasificar("alguien@random.com", true);

        assertThat(c.destino()).isEqualTo(Destino.SIN_AUTORIZAR);
        assertThat(c.motivo()).contains("desconocido");
        assertThat(c.odontologoId()).isNull();
    }

    @Test
    void elMailQueDiceSerDeUnOdontologoPeroNoPasaLaVerificacionQuedaSinAutorizar() {
        fichaDePerez();

        Clasificacion c = servicio.clasificar("perez@clinica.com", false);

        assertThat(c.destino()).isEqualTo(Destino.SIN_AUTORIZAR);
        assertThat(c.motivo()).contains("haciéndose pasar");
        assertThat(c.odontologoNombre()).as("se guarda lo que afirma ser, para que la persona lo vea").isEqualTo("Dr. Carlos Pérez");
        assertThat(c.verificado()).isFalse();
    }

    @Test
    void sinInformacionDeVerificacionTambienQuedaSinAutorizarSalvoQueSeDesactiveLaExigencia() {
        fichaDePerez();

        assertThat(servicio.clasificar("perez@clinica.com", null).destino()).isEqualTo(Destino.SIN_AUTORIZAR);

        props.setExigirVerificacion(false);
        assertThat(servicio.clasificar("perez@clinica.com", null).destino()).isEqualTo(Destino.CONOCIDO);
    }

    @Test
    void unMailSinRemitenteQuedaSinAutorizar() {
        assertThat(servicio.clasificar(null, true).destino()).isEqualTo(Destino.SIN_AUTORIZAR);
        assertThat(servicio.clasificar("  ", true).destino()).isEqualTo(Destino.SIN_AUTORIZAR);
    }

    // ── Bloqueados y topes ───────────────────────────────────────────

    @Test
    void unRemitenteBloqueadoSeIgnora() {
        RemitenteAutorizado spam = RemitenteAutorizado.builder().id(3L).email("spam@raro.com").tipo(RemitenteTipo.OTRO)
                .estado(RemitenteAutorizado.Estado.BLOQUEADO).build();
        when(remitenteRepo.findByEmailIgnoreCase("spam@raro.com")).thenReturn(Optional.of(spam));

        assertThat(servicio.clasificar("spam@raro.com", true).destino()).isEqualTo(Destino.IGNORAR);
    }

    @Test
    void despuesDelTopePorRemitenteSeIgnoranLosSiguientes() {
        props.setMaxCuarentenaPorRemitente(3);
        when(solicitudRepo.countByRemitenteEmailAndEstadoAndFechaRecepcionAfter(eq("insistente@raro.com"),
                eq(SolicitudEntrante.Estado.SIN_AUTORIZAR), any(LocalDateTime.class))).thenReturn(3L);

        Clasificacion c = servicio.clasificar("insistente@raro.com", true);

        assertThat(c.destino()).isEqualTo(Destino.IGNORAR);
        assertThat(c.motivo()).contains("ya dejó 3");
    }

    @Test
    void conLaBandejaDeSinAutorizarLlenaSeIgnoraTodoLoNuevoPeroNoLoConocido() {
        props.setMaxCuarentenaTotal(200);
        when(solicitudRepo.countByEstado(SolicitudEntrante.Estado.SIN_AUTORIZAR)).thenReturn(200L);
        fichaDePerez();

        assertThat(servicio.clasificar("otro@random.com", true).destino()).isEqualTo(Destino.IGNORAR);
        assertThat(servicio.clasificar("perez@clinica.com", true).destino()).as("un odontólogo verificado nunca se pierde").isEqualTo(Destino.CONOCIDO);
    }

    // ── Lo que decide una persona ────────────────────────────────────

    @Test
    void autorizarUnaSecretariaLaGuardaEnMinusculasConSuEtiqueta() {
        fichaDePerez();

        RemitenteAutorizado r = servicio.autorizar(" Laura@Consultorio.com ", RemitenteTipo.SECRETARIA, 5L, "Laura");

        assertThat(r.getEmail()).isEqualTo("laura@consultorio.com");
        assertThat(r.getTipo()).isEqualTo(RemitenteTipo.SECRETARIA);
        assertThat(r.getOdontologoId()).isEqualTo(5L);
        assertThat(r.getEtiqueta()).isEqualTo("Secretaria de Dr. Carlos Pérez (Laura)");
        assertThat(r.getEstado()).isEqualTo(RemitenteAutorizado.Estado.AUTORIZADO);
        assertThat(r.getFechaCreacion()).isNotNull();
        verify(auditoria).registrar(anyString(), eq("SOLICITUD"), eq("Remitente autorizado"), eq("laura@consultorio.com"), anyString());
    }

    @Test
    void autorizarUnaDireccionBloqueadaLaDesbloquea() {
        fichaDePerez();
        RemitenteAutorizado bloqueada = RemitenteAutorizado.builder().id(9L).email("laura@consultorio.com").tipo(RemitenteTipo.OTRO)
                .estado(RemitenteAutorizado.Estado.BLOQUEADO).creadoPor("ana").fechaCreacion(LocalDateTime.of(2026, 10, 1, 10, 0)).build();
        when(remitenteRepo.findByEmailIgnoreCase("laura@consultorio.com")).thenReturn(Optional.of(bloqueada));

        RemitenteAutorizado r = servicio.autorizar("laura@consultorio.com", RemitenteTipo.SECRETARIA, 5L, null);

        assertThat(r.getId()).isEqualTo(9L);
        assertThat(r.getEstado()).isEqualTo(RemitenteAutorizado.Estado.AUTORIZADO);
    }

    @Test
    void autorizarExigeUnaDireccionValidaYElOdontologoSiCorresponde() {
        assertThatThrownBy(() -> servicio.autorizar("no-es-un-mail", RemitenteTipo.OTRO, null, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("no es válida");
        assertThatThrownBy(() -> servicio.autorizar("laura@consultorio.com", RemitenteTipo.SECRETARIA, null, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("odontólogo");
        verify(remitenteRepo, never()).save(any());
    }

    @Test
    void bloquearDescartaTodoLoQueEseRemitenteTeniaEsperando() {
        SolicitudEntrante a = SolicitudEntrante.builder().id(1L).estado(SolicitudEntrante.Estado.SIN_AUTORIZAR).remitenteEmail("spam@raro.com").build();
        SolicitudEntrante b = SolicitudEntrante.builder().id(2L).estado(SolicitudEntrante.Estado.SIN_AUTORIZAR).remitenteEmail("spam@raro.com").build();
        when(solicitudRepo.findByRemitenteEmailAndEstado("spam@raro.com", SolicitudEntrante.Estado.SIN_AUTORIZAR)).thenReturn(List.of(a, b));

        int descartadas = servicio.bloquear("Spam@Raro.com");

        assertThat(descartadas).isEqualTo(2);
        assertThat(a.getEstado()).isEqualTo(SolicitudEntrante.Estado.DESCARTADA);
        assertThat(a.getMotivoDescarte()).isEqualTo("Remitente bloqueado");
        verify(remitenteRepo).save(org.mockito.ArgumentMatchers.argThat(r ->
                r.getEmail().equals("spam@raro.com") && r.getEstado() == RemitenteAutorizado.Estado.BLOQUEADO));
    }

    @Test
    void noSePuedeBloquearLaDireccionDeUnOdontologoNiUnaAutorizada() {
        fichaDePerez();
        assertThatThrownBy(() -> servicio.bloquear("perez@clinica.com"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("odontólogo");

        RemitenteAutorizado laura = RemitenteAutorizado.builder().id(2L).email("laura@consultorio.com").tipo(RemitenteTipo.SECRETARIA)
                .estado(RemitenteAutorizado.Estado.AUTORIZADO).build();
        when(remitenteRepo.findByEmailIgnoreCase("laura@consultorio.com")).thenReturn(Optional.of(laura));
        assertThatThrownBy(() -> servicio.bloquear("laura@consultorio.com"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("autorizada");

        verify(remitenteRepo, never()).save(any());
    }

    @Test
    void quitarUnaDireccionLaSacaDeLaLista() {
        RemitenteAutorizado laura = RemitenteAutorizado.builder().id(2L).email("laura@consultorio.com").tipo(RemitenteTipo.SECRETARIA)
                .etiqueta("Secretaria").estado(RemitenteAutorizado.Estado.AUTORIZADO).build();
        when(remitenteRepo.findById(2L)).thenReturn(Optional.of(laura));

        servicio.quitar(2L);

        verify(remitenteRepo).delete(laura);
        verify(auditoria).registrar(anyString(), eq("SOLICITUD"), eq("Remitente quitado de los autorizados"), eq("laura@consultorio.com"), anyString());
    }
}
