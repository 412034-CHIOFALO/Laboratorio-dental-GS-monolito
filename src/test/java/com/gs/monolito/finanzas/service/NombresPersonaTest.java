package com.gs.monolito.finanzas.service;

import com.gs.monolito.finanzas.service.NombresPersona.Parecido;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El caso que motivó todo esto: un pago de "Juan Pérez" no puede saldar la deuda de "Juan Carlos Gómez" porque
 * compartan una palabra. Una palabra suelta nunca alcanza para decir que son la misma persona.
 */
class NombresPersonaTest {

    private static Parecido p(String a, String b) {
        return NombresPersona.comparar(a, b);
    }

    @Test
    void elMismoNombreEsExacto_sinImportarElOrdenLosAcentosNiLosTitulos() {
        assertThat(p("Juan Pérez", "juan perez")).isEqualTo(Parecido.EXACTO);
        assertThat(p("Dr. Juan Pérez", "PÉREZ JUAN")).isEqualTo(Parecido.EXACTO);
        assertThat(p("Dra. María de los Ángeles Gómez", "Gomez Maria Angeles")).isEqualTo(Parecido.EXACTO);
        assertThat(p("Pérez, Juan", "Juan  Pérez")).isEqualTo(Parecido.EXACTO);
    }

    @Test
    void compartirUnaSolaPalabraNoEsSerLaMismaPersona() {
        assertThat(p("Juan Pérez", "Juan Carlos Gómez")).isEqualTo(Parecido.NINGUNO);
        assertThat(p("Juan Pérez", "Ana Pérez")).isEqualTo(Parecido.NINGUNO);
        assertThat(p("Martín López", "Martín García")).isEqualTo(Parecido.NINGUNO);
    }

    @Test
    void unaPalabraSolaNuncaCoincideConUnNombreCompleto() {
        assertThat(p("Juan", "Juan Pérez")).isEqualTo(Parecido.NINGUNO);
        assertThat(p("Pérez", "Juan Pérez")).isEqualTo(Parecido.NINGUNO);
    }

    @Test
    void unNombreMasLargoQueContieneAlOtroEsContenido_noExacto() {
        assertThat(p("Juan Pérez", "Juan Carlos Pérez")).isEqualTo(Parecido.CONTENIDO);
        assertThat(p("Pérez Juan Carlos", "Dr. Juan Pérez")).isEqualTo(Parecido.CONTENIDO);
    }

    @Test
    void lasPalabrasDeRelleno_noCuentan() {
        assertThat(p("de la Torre", "Dr. Torre")).isEqualTo(Parecido.EXACTO);
        assertThat(p("Dr.", "Dra.")).isEqualTo(Parecido.NINGUNO);
    }

    @Test
    void nombresVaciosONulosNoCoinciden() {
        assertThat(p(null, "Juan Pérez")).isEqualTo(Parecido.NINGUNO);
        assertThat(p("  ", "Juan Pérez")).isEqualTo(Parecido.NINGUNO);
        assertThat(p("", "")).isEqualTo(Parecido.NINGUNO);
    }

    @Test
    void elReceptorGuardadoPuedeTenerPalabrasDeMas_peroElDelComprobanteNo() {
        assertThat(NombresPersona.coincideReceptor("Lucía Benítez (demo)", "LUCIA BENITEZ")).isTrue();
        assertThat(NombresPersona.coincideReceptor("María José Gómez", "Gómez María")).isTrue();
        assertThat(NombresPersona.coincideReceptor("Carlos López", "Carlos")).isFalse();
        assertThat(NombresPersona.coincideReceptor("Carlos López", "Carlos Gómez")).isFalse();
        assertThat(NombresPersona.coincideReceptor("Carlos López", "de")).isFalse();
    }

    @Test
    void unNombreDeUnaPalabraSoloCoincideConElMismo() {
        assertThat(NombresPersona.coincideReceptor("Metalmax", "metalmax")).isTrue();
        assertThat(NombresPersona.coincideReceptor("Metalmax SRL", "Metalmax")).isFalse();
    }

    @Test
    void enumeraCandidatosEnCastellano() {
        assertThat(NombresPersona.enumerar(List.of("Ana"))).isEqualTo("Ana");
        assertThat(NombresPersona.enumerar(List.of("Ana", "Beto"))).isEqualTo("Ana o Beto");
        assertThat(NombresPersona.enumerar(List.of("Ana", "Beto", "Carla"))).isEqualTo("Ana, Beto o Carla");
    }
}
