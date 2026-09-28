package com.gs.monolito.pedidos.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El tipo con el que se sirve un archivo subido sale solo de su extensión —
 * nunca del Content-Type que mandó quien lo subió (ver TiposArchivo).
 */
class TiposArchivoTest {

    @Test
    void pdfEImagenesSeMuestranEnElNavegadorConSuTipoReal() {
        assertThat(TiposArchivo.mediaTypePara("orden.pdf")).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(TiposArchivo.mediaTypePara("foto.JPG")).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(TiposArchivo.mediaTypePara("foto.png")).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(TiposArchivo.contentDisposition("orden.pdf")).startsWith("inline");
    }

    @Test
    void htmlSvgYDesconocidosSalenComoBinarioYDescarga() {
        for (String nombre : new String[]{"ataque.html", "ataque.svg", "script.js", "sin-extension", null}) {
            assertThat(TiposArchivo.mediaTypePara(nombre)).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
            assertThat(TiposArchivo.contentDisposition(nombre)).startsWith("attachment");
        }
    }

    @Test
    void escaneos3dSalenComoDescarga() {
        assertThat(TiposArchivo.mediaTypePara("arcada.stl")).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(TiposArchivo.contentDisposition("arcada.stl")).startsWith("attachment");
    }

    @Test
    void unNombreConComillasOSaltosDeLineaNoRompeElHeader() {
        String header = TiposArchivo.contentDisposition("a\"b\r\nSet-Cookie: x=1.pdf");

        assertThat(header).doesNotContain("\r").doesNotContain("\n");
        assertThat(header).startsWith("inline");
    }
}
