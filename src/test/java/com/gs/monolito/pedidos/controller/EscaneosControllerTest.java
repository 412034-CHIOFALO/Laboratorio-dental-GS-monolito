package com.gs.monolito.pedidos.controller;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.pedidos.dto.EscaneoResponse;
import com.gs.monolito.pedidos.exception.BusinessException;
import com.gs.monolito.pedidos.model.EscaneosPedido;
import com.gs.monolito.pedidos.model.Pedido;
import com.gs.monolito.pedidos.repository.EscaneosPedidoRepository;
import com.gs.monolito.pedidos.repository.PedidoRepository;
import com.gs.monolito.pedidos.service.MallaInspector;
import com.gs.monolito.pedidos.service.PedidosDocumentoStorageService;
import com.gs.monolito.pedidos.service.UploadValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La subida de un escaneo: se revisa por dentro antes de guardar, no se cargan dos iguales en un pedido, hay un tope
 * por pedido, y si la base falla después de subir el archivo no queda un objeto huérfano en MinIO.
 */
class EscaneosControllerTest {

    private static final String STL = "solid t\nfacet normal 0 0 0\nouter loop\nvertex 0 0 0\nvertex 10 0 0\nvertex 0 20 0\n"
            + "endloop\nendfacet\nendsolid t\n";

    private EscaneosPedidoRepository escaneoRepo;
    private PedidoRepository pedidoRepo;
    private PedidosDocumentoStorageService minio;
    private AuditoriaService auditoria;
    private EscaneosController controller;
    private final Authentication auth = new UsernamePasswordAuthenticationToken("tecnico1", "x");

    @BeforeEach
    void preparar() {
        escaneoRepo = mock(EscaneosPedidoRepository.class);
        pedidoRepo = mock(PedidoRepository.class);
        minio = mock(PedidosDocumentoStorageService.class);
        auditoria = mock(AuditoriaService.class);
        controller = new EscaneosController(escaneoRepo, pedidoRepo, minio, mock(UploadValidator.class),
                new MallaInspector(MallaInspector.MAX_TRIANGULOS_DEFECTO), auditoria);
        when(pedidoRepo.findById(1L)).thenReturn(Optional.of(Pedido.builder().id(1L).nroPedido("PED-20261021-0001").build()));
        when(escaneoRepo.save(any(EscaneosPedido.class))).thenAnswer(inv -> inv.getArgument(0));
        when(minio.subirStream(any(InputStream.class), anyLong(), anyString(), anyString(), eq(1L), eq("escaneos")))
                .thenReturn("escaneos/1/2026/10/abc.stl");
    }

    private static MockMultipartFile archivo(String nombre, String contenido) {
        return new MockMultipartFile("file", nombre, "application/octet-stream", contenido.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void unaSubidaValidaGuardaLoMedidoYQuedaAuditada() {
        EscaneoResponse r = controller.subir(1L, archivo("arcada.stl", STL), "Arcada superior", auth).getBody();

        assertThat(r.formato()).isEqualTo("STL_ASCII");
        assertThat(r.triangulos()).isEqualTo(1);
        assertThat(r.dimXMm()).isEqualByComparingTo("10");
        assertThat(r.dimYMm()).isEqualByComparingTo("20");
        ArgumentCaptor<EscaneosPedido> guardado = ArgumentCaptor.forClass(EscaneosPedido.class);
        verify(escaneoRepo).save(guardado.capture());
        assertThat(guardado.getValue().getSha256()).hasSize(64);
        assertThat(guardado.getValue().getObjectKey()).isEqualTo("escaneos/1/2026/10/abc.stl");
        verify(auditoria).registrar(eq("tecnico1"), eq("ESCANEO"), eq("Escaneo subido"), eq("Pedido PED-20261021-0001"), anyString());
    }

    @Test
    void unArchivoRotoSeRechazaAntesDeTocarMinIO() {
        MockMultipartFile roto = archivo("roto.stl", STL.replace("vertex 0 20 0\n", ""));   // un triángulo con dos vértices

        assertThatThrownBy(() -> controller.subir(1L, roto, null, auth))
                .isInstanceOf(MallaInspector.MallaInvalidaException.class).hasMessageContaining("incompleto");

        verify(minio, never()).subirStream(any(), anyLong(), anyString(), anyString(), anyLong(), anyString());
        verify(escaneoRepo, never()).save(any());
    }

    @Test
    void elMismoArchivoNoSeCargaDosVecesEnUnPedido() {
        when(escaneoRepo.findFirstByPedidoIdAndSha256(eq(1L), anyString()))
                .thenReturn(Optional.of(EscaneosPedido.builder().id(5L).fileName("primera-vez.stl").build()));

        assertThatThrownBy(() -> controller.subir(1L, archivo("otra-vez.stl", STL), null, auth))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ya está cargado").hasMessageContaining("primera-vez.stl");

        verify(minio, never()).subirStream(any(), anyLong(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void unPedidoNoPuedeTenerMasEscaneosQueElTope() {
        ReflectionTestUtils.setField(controller, "maxPorPedido", 3);
        when(escaneoRepo.countByPedidoId(1L)).thenReturn(3L);

        assertThatThrownBy(() -> controller.subir(1L, archivo("a.stl", STL), null, auth))
                .isInstanceOf(BusinessException.class).hasMessageContaining("máximo");

        verify(minio, never()).subirStream(any(), anyLong(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void siLaBaseFallaDespuesDeSubirElArchivoSeBorraDeMinIO() {
        when(escaneoRepo.save(any(EscaneosPedido.class))).thenThrow(new IllegalStateException("base caída"));

        assertThatThrownBy(() -> controller.subir(1L, archivo("a.stl", STL), null, auth))
                .isInstanceOf(IllegalStateException.class);

        verify(minio).eliminar("escaneos/1/2026/10/abc.stl");
        verify(auditoria, never()).registrar(any(), any(), any(), any(), any());
    }

    @Test
    void siMinIONoEstaDisponibleNoSeGuardaNada() {
        when(minio.subirStream(any(InputStream.class), anyLong(), anyString(), anyString(), eq(1L), eq("escaneos"))).thenReturn(null);

        assertThatThrownBy(() -> controller.subir(1L, archivo("a.stl", STL), null, auth))
                .isInstanceOf(BusinessException.class).hasMessageContaining("MinIO no disponible");

        verify(escaneoRepo, never()).save(any());
    }

    @Test
    void elNombreDelArchivoSeLimpiaDeRutasYCaracteresDeControl() {
        assertThat(EscaneosController.nombreSeguro("C:\\scans\\Arcada.stl")).isEqualTo("Arcada.stl");
        assertThat(EscaneosController.nombreSeguro("../../etc/passwd.stl")).isEqualTo("passwd.stl");
        assertThat(EscaneosController.nombreSeguro("a\u0000b\nc.stl")).isEqualTo("abc.stl");
        assertThat(EscaneosController.nombreSeguro("  ")).isEqualTo("escaneo");
        assertThat(EscaneosController.nombreSeguro(null)).isEqualTo("escaneo");
        String largo = "x".repeat(400) + ".stl";
        assertThat(EscaneosController.nombreSeguro(largo)).hasSize(200).endsWith(".stl");
    }

    @Test
    void borrarUnEscaneoQuedaAuditado() {
        when(escaneoRepo.findById(7L)).thenReturn(Optional.of(
                EscaneosPedido.builder().id(7L).pedidoId(1L).objectKey("escaneos/1/x.stl").fileName("arcada.stl").triangulos(1200L).build()));

        controller.eliminar(1L, 7L, auth);

        verify(minio).eliminar("escaneos/1/x.stl");
        verify(escaneoRepo).delete(any(EscaneosPedido.class));
        verify(auditoria).registrar(eq("tecnico1"), eq("ESCANEO"), eq("Escaneo eliminado"), eq("Pedido PED-20261021-0001"), eq("arcada.stl · 1200 triángulos"));
    }
}
