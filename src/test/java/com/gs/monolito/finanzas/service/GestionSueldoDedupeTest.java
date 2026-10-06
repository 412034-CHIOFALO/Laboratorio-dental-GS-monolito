package com.gs.monolito.finanzas.service;

import com.gs.monolito.finanzas.dto.PagoAutomaticoRequest;
import com.gs.monolito.finanzas.dto.PagoEfectivoRequest;
import com.gs.monolito.finanzas.dto.RegistroPagoBotResponse;
import com.gs.monolito.finanzas.model.EstadoRegistroBot;
import com.gs.monolito.finanzas.model.RegistroPagoBot;
import com.gs.monolito.finanzas.repository.RegistroPagoBotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El bot de WhatsApp puede reenviar un mensaje que el sistema ya procesó (reconciliación tras
 * una desconexión, reinicio, pérdida de su archivo de estado). El backend es la memoria que
 * manda: un mensaje ya registrado NO crea un registro ni un pago nuevo.
 */
@ExtendWith(MockitoExtension.class)
class GestionSueldoDedupeTest {

    @Mock private RegistroPagoBotRepository registroRepo;
    @InjectMocks private GestionSueldoService service;

    private static final String ID_MSG = "true_120363000000000000@g.us_3EB0ABCDEF_5491100000000@c.us";

    @BeforeEach
    void guardarDevuelveLoMismo() {
        org.mockito.Mockito.lenient().when(registroRepo.save(any(RegistroPagoBot.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private PagoAutomaticoRequest transferencia(String idMensaje, String idOperacion, String archivo) {
        PagoAutomaticoRequest req = new PagoAutomaticoRequest();
        req.setReceptorNombre("Carlos López");
        req.setMonto(new BigDecimal("85000"));
        req.setEmisor("Dr. García");
        req.setIdMensaje(idMensaje);
        req.setIdOperacion(idOperacion);
        if (archivo != null) {
            req.setComprobanteBase64(Base64.getEncoder().encodeToString(archivo.getBytes()));
            req.setComprobanteMime("application/pdf");
            req.setComprobanteNombre("comprobante.pdf");
        }
        return req;
    }

    @Test
    void unMensajeYaRegistradoDevuelveElMismoRegistroSinCrearNada() {
        RegistroPagoBot existente = RegistroPagoBot.builder().id(7L).idMensajeWa(ID_MSG)
                .estado(EstadoRegistroBot.REGISTRADO).mensaje("Sueldo registrado").build();
        when(registroRepo.findFirstByIdMensajeWaOrIdMensajePie(ID_MSG, ID_MSG)).thenReturn(Optional.of(existente));

        RegistroPagoBotResponse r = service.registrarPagoAutomatico(transferencia(ID_MSG, null, "pdf-1"));

        assertThat(r.repetido()).isTrue();
        assertThat(r.id()).isEqualTo(7L);
        assertThat(r.estado()).isEqualTo(EstadoRegistroBot.REGISTRADO);
        verify(registroRepo, never()).save(any());
    }

    @Test
    void elMismoArchivoSeDetectaComoDuplicadoAunSinNumeroDeOperacion() {
        when(registroRepo.findFirstByIdMensajeWaOrIdMensajePie(anyString(), anyString())).thenReturn(Optional.empty());
        when(registroRepo.existsByHashComprobanteAndEstado(anyString(), eq(EstadoRegistroBot.REGISTRADO))).thenReturn(true);

        // mensaje distinto (otro ID de WhatsApp), pero el archivo es byte a byte el mismo
        RegistroPagoBotResponse r = service.registrarPagoAutomatico(transferencia("otro-mensaje", null, "pdf-1"));

        assertThat(r.estado()).isEqualTo(EstadoRegistroBot.DUPLICADO);
        assertThat(r.repetido()).isFalse();
        assertThat(r.mensaje()).contains("mismo archivo");
        ArgumentCaptor<RegistroPagoBot> guardado = ArgumentCaptor.forClass(RegistroPagoBot.class);
        verify(registroRepo).save(guardado.capture());
        // queda anotado el ID del mensaje y el hash (SHA-256 = 64 hex) para futuras consultas
        assertThat(guardado.getValue().getIdMensajeWa()).isEqualTo("otro-mensaje");
        assertThat(guardado.getValue().getHashComprobante()).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void elHashEsElDeLosBytesDelArchivo() {
        when(registroRepo.findFirstByIdMensajeWaOrIdMensajePie(anyString(), anyString())).thenReturn(Optional.empty());
        when(registroRepo.existsByHashComprobanteAndEstado(anyString(), eq(EstadoRegistroBot.REGISTRADO))).thenReturn(true);

        service.registrarPagoAutomatico(transferencia("m1", null, "hola"));

        ArgumentCaptor<RegistroPagoBot> guardado = ArgumentCaptor.forClass(RegistroPagoBot.class);
        verify(registroRepo).save(guardado.capture());
        // sha256("hola")
        assertThat(guardado.getValue().getHashComprobante())
                .isEqualTo("b221d9dbb083a7f33428d7c2a3c3198ae925614d70210e28716ccaa7cd4ddb79");
    }

    @Test
    void elEfectivoRepetidoPorIdDeMensajeNoCreaOtroBorrador() {
        RegistroPagoBot existente = RegistroPagoBot.builder().id(9L).idMensajeWa(ID_MSG)
                .estado(EstadoRegistroBot.PENDIENTE).build();
        when(registroRepo.findFirstByIdMensajeWaOrIdMensajePie(ID_MSG, ID_MSG)).thenReturn(Optional.of(existente));

        PagoEfectivoRequest req = new PagoEfectivoRequest();
        req.setReceptorNombre("Carlos López");
        req.setMonto(new BigDecimal("85000"));
        req.setIdMensaje(ID_MSG);

        RegistroPagoBotResponse r = service.registrarPagoEfectivo(req);

        assertThat(r.repetido()).isTrue();
        assertThat(r.id()).isEqualTo(9L);
        verify(registroRepo, never()).save(any());
    }

    @Test
    void elEfectivoNuevoGuardaElIdDelMensaje() {
        when(registroRepo.findFirstByIdMensajeWaOrIdMensajePie(anyString(), anyString())).thenReturn(Optional.empty());

        PagoEfectivoRequest req = new PagoEfectivoRequest();
        req.setReceptorNombre("Carlos López");
        req.setMonto(new BigDecimal("85000"));
        req.setIdMensaje(ID_MSG);

        RegistroPagoBotResponse r = service.registrarPagoEfectivo(req);

        assertThat(r.repetido()).isFalse();
        assertThat(r.estado()).isEqualTo(EstadoRegistroBot.PENDIENTE);
        ArgumentCaptor<RegistroPagoBot> guardado = ArgumentCaptor.forClass(RegistroPagoBot.class);
        verify(registroRepo).save(guardado.capture());
        assertThat(guardado.getValue().getIdMensajeWa()).isEqualTo(ID_MSG);
    }

    @Test
    void sinIdDeMensajeSeRegistraComoSiempre() {
        PagoEfectivoRequest req = new PagoEfectivoRequest();
        req.setReceptorNombre("Carlos López");
        req.setMonto(new BigDecimal("85000"));

        RegistroPagoBotResponse r = service.registrarPagoEfectivo(req);

        assertThat(r.repetido()).isFalse();
        verify(registroRepo, never()).findFirstByIdMensajeWaOrIdMensajePie(any(), any());
        verify(registroRepo).save(any(RegistroPagoBot.class));
    }

    @Test
    void mensajesConocidosUneLosQueSonComprobanteYLosQueSonPie() {
        when(registroRepo.idsMensajeWaConocidos(List.of("a", "b", "c"))).thenReturn(List.of("a"));
        when(registroRepo.idsMensajePieConocidos(List.of("a", "b", "c"))).thenReturn(List.of("c", "a"));

        List<String> conocidos = service.mensajesConocidos(java.util.Arrays.asList("a", "b", "c", "a", " ", null));

        assertThat(conocidos).containsExactlyInAnyOrder("a", "c");
    }

    @Test
    void mensajesConocidosSinIdsNoConsultaNada() {
        assertThat(service.mensajesConocidos(java.util.Arrays.asList(" ", null))).isEmpty();
        verify(registroRepo, never()).idsMensajeWaConocidos(any());
    }
}
