package com.gs.monolito.finanzas.service;

import com.gs.monolito.finanzas.dto.PagoAutomaticoRequest;
import com.gs.monolito.finanzas.dto.RegistroPagoBotResponse;
import com.gs.monolito.finanzas.model.ConfiguracionSueldo;
import com.gs.monolito.finanzas.model.EstadoRegistroBot;
import com.gs.monolito.finanzas.model.FuentePago;
import com.gs.monolito.finanzas.model.RegistroPagoBot;
import com.gs.monolito.finanzas.repository.ConfiguracionSueldoRepository;
import com.gs.monolito.finanzas.repository.PagoSueldoRepository;
import com.gs.monolito.finanzas.repository.ProveedorRepository;
import com.gs.monolito.finanzas.repository.RegistroPagoBotRepository;
import com.gs.monolito.auth.service.AuditoriaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Qué hace el backend con el veredicto del semáforo: lo dudoso NO se aplica (queda pendiente, con sus
 * motivos), pero un receptor desconocido se rechaza igual — no hay nada que revisar.
 */
@ExtendWith(MockitoExtension.class)
class GestionSueldoSemaforoTest {

    @Mock private RegistroPagoBotRepository registroRepo;
    @Mock private ConfiguracionSueldoRepository configRepo;
    @Mock private ProveedorRepository proveedorRepo;
    @Mock private PagoSueldoRepository pagoRepo;
    @Mock private AuditoriaService auditoria;
    @Mock private RevisionPago revision;
    @InjectMocks private GestionSueldoService service;

    @BeforeEach
    void guardarDevuelveLoMismo() {
        org.mockito.Mockito.lenient().when(registroRepo.save(any(RegistroPagoBot.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private PagoAutomaticoRequest transferencia(String receptor) {
        PagoAutomaticoRequest r = new PagoAutomaticoRequest();
        r.setReceptorNombre(receptor);
        r.setEmisor("Dr. García");
        r.setMonto(new BigDecimal("85000"));
        r.setIdOperacion("98765432101");
        r.setIdMensaje("msg-1");
        return r;
    }

    private void hayUnEmpleado() {
        when(configRepo.findAllByOrderByEmpleadoNombreAsc()).thenReturn(List.of(
                ConfiguracionSueldo.builder().empleadoId(2L).empleadoNombre("Carlos López").activo(true).build()));
    }

    @Test
    void unaTransferenciaDudosaAUnEmpleadoConocidoQuedaPendienteYNoSeAplica() {
        hayUnEmpleado();
        when(revision.motivos(any())).thenReturn(List.of("No se pudo leer la fecha del comprobante", "Monto alto: $ 200.000"));

        RegistroPagoBotResponse r = service.registrarPagoAutomatico(transferencia("Carlos López"));

        assertThat(r.estado()).isEqualTo(EstadoRegistroBot.PENDIENTE);
        assertThat(r.fuente()).isEqualTo(FuentePago.TRANSFERENCIA);
        assertThat(r.motivosRevision()).contains("No se pudo leer la fecha").contains("Monto alto").contains(" | ");
        assertThat(r.mensaje()).startsWith("Pendiente de revisión");
        verify(pagoRepo, never()).save(any());   // no se pagó nada: ni sueldo ni caja
    }

    @Test
    void unaTransferenciaDudosaConReceptorDesconocidoSeRechazaIgual() {
        when(configRepo.findAllByOrderByEmpleadoNombreAsc()).thenReturn(List.of());
        when(revision.motivos(any())).thenReturn(List.of("No se pudo leer la fecha del comprobante"));

        RegistroPagoBotResponse r = service.registrarPagoAutomatico(transferencia("Persona Desconocida"));

        assertThat(r.estado()).isEqualTo(EstadoRegistroBot.RECHAZADO);
        assertThat(r.motivosRevision()).isNull();
    }

    @Test
    void elRegistroPendienteGuardaLosDatosParaAplicarloDespues() {
        hayUnEmpleado();
        when(revision.motivos(any())).thenReturn(List.of("Rastros de edición en el archivo: photoshop"));

        service.registrarPagoAutomatico(transferencia("Carlos López"));

        ArgumentCaptor<RegistroPagoBot> guardado = ArgumentCaptor.forClass(RegistroPagoBot.class);
        verify(registroRepo).save(guardado.capture());
        RegistroPagoBot reg = guardado.getValue();
        assertThat(reg.getMonto()).isEqualByComparingTo("85000");
        assertThat(reg.getReceptorNombre()).isEqualTo("Carlos López");
        assertThat(reg.getEmisor()).isEqualTo("Dr. García");
        assertThat(reg.getIdOperacion()).isEqualTo("98765432101");
        assertThat(reg.getIdMensajeWa()).isEqualTo("msg-1");
        assertThat(reg.getEstado()).isEqualTo(EstadoRegistroBot.PENDIENTE);
    }

    @Test
    void laListaDeRevisionSoloTraeTransferenciasPendientes() {
        RegistroPagoBot pendiente = RegistroPagoBot.builder().id(5L).estado(EstadoRegistroBot.PENDIENTE)
                .fuente(FuentePago.TRANSFERENCIA).motivosRevision("x").build();
        when(registroRepo.findByEstadoAndFuenteOrderByFechaHoraDesc(EstadoRegistroBot.PENDIENTE, FuentePago.TRANSFERENCIA))
                .thenReturn(List.of(pendiente));

        assertThat(service.listarPendientesRevision()).extracting(RegistroPagoBotResponse::id).containsExactly(5L);
        verify(registroRepo, never()).findByEstadoAndFuenteOrderByFechaHoraDesc(EstadoRegistroBot.PENDIENTE, FuentePago.EFECTIVO);
    }
}
