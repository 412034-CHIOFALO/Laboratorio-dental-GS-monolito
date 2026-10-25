package com.gs.monolito.common.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El "qué cambió" de la bitácora: tiene que decir lo justo (solo lo que cambió), no filtrar datos personales
 * completos y no pasarse de la columna de 500 caracteres (si no, la auditoría haría fallar la operación).
 */
class CambiosTest {

    @Test
    void soloAparecenLosCamposQueCambiaron() {
        Cambios c = Cambios.nuevo()
                .texto("clínica", "Norte", "Sur")
                .texto("dirección", "Mitre 100", "Mitre 100");

        assertThat(c.hayCambios()).isTrue();
        assertThat(c.resumen()).isEqualTo("clínica: Norte → Sur");
    }

    @Test
    void sinCambiosNoHayNadaQueRegistrar() {
        Cambios c = Cambios.nuevo().texto("clínica", "Norte", "Norte").sensible("teléfono", "1155554521", "1155554521");

        assertThat(c.hayCambios()).isFalse();
        assertThat(c.resumen()).isEmpty();
    }

    @Test
    void elTelefonoSeMuestraEnmascarado_conLosUltimosCuatroDigitos() {
        Cambios c = Cambios.nuevo().sensible("teléfono", "+54 9 11 5555-4521", "011 4444-9876");

        assertThat(c.resumen()).isEqualTo("teléfono: …4521 → …9876");
        assertThat(c.resumen()).doesNotContain("5555").doesNotContain("4444");
    }

    @Test
    void elEmailSeMuestraSinElUsuarioCompleto() {
        Cambios c = Cambios.nuevo().sensible("email", "juan.perez@clinica.com", "juanp@otra.com");

        assertThat(c.resumen()).isEqualTo("email: j***@clinica.com → j***@otra.com");
    }

    @Test
    void cargarUnDatoDondeNoHabiaNada_muestraVacioComoOrigen() {
        Cambios c = Cambios.nuevo().sensible("teléfono", null, "1155554521").texto("clínica", "Norte", null);

        assertThat(c.resumen()).isEqualTo("teléfono: (vacío) → …4521; clínica: Norte → (vacío)");
    }

    @Test
    void nuloEnBlancoYEspaciosSonLoMismo() {
        Cambios c = Cambios.nuevo().texto("clínica", null, "   ").texto("dirección", " Mitre ", "Mitre");

        assertThat(c.hayCambios()).isFalse();
    }

    @Test
    void elValorDeUnAltaSeAnotaSoloSiHayDato() {
        Cambios c = Cambios.nuevo()
                .dato("clínica", "Norte").dato("matrícula", null)
                .datoSensible("teléfono", "1155554521").datoSensible("email", "  ");

        assertThat(c.resumen()).isEqualTo("clínica: Norte; teléfono: …4521");
    }

    @Test
    void unDatoMuyCortoNoDejaVerNingunDigito() {
        assertThat(Cambios.enmascarar("123")).isEqualTo("***");
    }

    @Test
    void elResumenNuncaPasaDeLaColumnaDeDetalle() {
        Cambios c = Cambios.nuevo();
        for (int i = 0; i < 60; i++) c.texto("campo" + i, "valor anterior largo " + i, "valor nuevo largo " + i);

        assertThat(c.resumen().length()).isLessThanOrEqualTo(Cambios.MAX);
        assertThat(c.resumen()).endsWith("…");
        assertThat(Cambios.MAX).isLessThan(500);
    }

    @Test
    void unImporteMuestraElAntesYElDespuesSinDecimalesDeRelleno() {
        Cambios c = Cambios.nuevo()
                .dinero("precio", new java.math.BigDecimal("15000.00"), new java.math.BigDecimal("18500.50"))
                .dinero("costo", new java.math.BigDecimal("1000"), new java.math.BigDecimal("1000.00"))
                .dinero("seña", null, new java.math.BigDecimal("2E+3"));

        assertThat(c.resumen()).isEqualTo("precio: $15000 → $18500.5; seña: (vacío) → $2000");
    }
}
