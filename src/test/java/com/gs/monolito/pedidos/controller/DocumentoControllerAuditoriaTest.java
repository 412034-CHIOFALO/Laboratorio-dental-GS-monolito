package com.gs.monolito.pedidos.controller;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.pedidos.model.DocumentoPedido;
import com.gs.monolito.pedidos.model.Pedido;
import com.gs.monolito.pedidos.repository.DocumentoPedidoRepository;
import com.gs.monolito.pedidos.repository.PedidoRepository;
import com.gs.monolito.pedidos.service.PedidosDocumentoStorageService;
import com.gs.monolito.pedidos.service.UploadValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Subir y borrar documentos de un pedido queda registrado; el borrado es irreversible (también sale de MinIO). */
@ExtendWith(MockitoExtension.class)
class DocumentoControllerAuditoriaTest {

    @Mock private DocumentoPedidoRepository docRepo;
    @Mock private PedidoRepository pedidoRepository;
    @Mock private PedidosDocumentoStorageService storage;
    @Mock private UploadValidator uploadValidator;
    @Mock private AuditoriaService auditoria;

    @InjectMocks private DocumentoController controller;

    private final UsernamePasswordAuthenticationToken mariana = new UsernamePasswordAuthenticationToken("mariana", "x");

    private Pedido pedido() {
        Pedido p = new Pedido();
        p.setNroPedido("PED-20261024-0003");
        return p;
    }

    @Test
    void subirUnDocumentoQuedaAuditado_conQuienLoSubioYElNombreDelArchivo() {
        when(pedidoRepository.existsById(9L)).thenReturn(true);
        when(pedidoRepository.findById(9L)).thenReturn(Optional.of(pedido()));
        when(storage.subir(any(), anyString(), anyString(), anyLong())).thenReturn("pedidos/9/receta.pdf");
        when(docRepo.save(any(DocumentoPedido.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.subir(9L, new MockMultipartFile("file", "receta.pdf", "application/pdf", new byte[2048]), mariana);

        ArgumentCaptor<String> detalle = ArgumentCaptor.forClass(String.class);
        verify(auditoria).registrar(eq("mariana"), eq("DOCUMENTO"), eq("Documento subido"), eq("Pedido PED-20261024-0003"), detalle.capture());
        assertThat(detalle.getValue()).isEqualTo("receta.pdf · 2 KB");
    }

    @Test
    void borrarUnDocumentoQuedaAuditado_conElNombreDelArchivoYQuienLoSubio() {
        DocumentoPedido doc = DocumentoPedido.builder().id(4L).pedidoId(9L).objectKey("pedidos/9/receta.pdf")
                .fileName("receta.pdf").subidoPor("juan").build();
        when(docRepo.findById(4L)).thenReturn(Optional.of(doc));
        when(pedidoRepository.findById(9L)).thenReturn(Optional.of(pedido()));

        controller.eliminar(9L, 4L, mariana);

        ArgumentCaptor<String> detalle = ArgumentCaptor.forClass(String.class);
        verify(auditoria).registrar(eq("mariana"), eq("DOCUMENTO"), eq("Documento eliminado"), eq("Pedido PED-20261024-0003"), detalle.capture());
        assertThat(detalle.getValue()).isEqualTo("receta.pdf · subido por juan");
        verify(docRepo).delete(doc);
    }
}
