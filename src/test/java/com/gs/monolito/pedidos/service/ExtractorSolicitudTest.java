package com.gs.monolito.pedidos.service;

import com.gs.monolito.pedidos.model.Prioridad;
import com.gs.monolito.pedidos.service.ExtractorSolicitud.ItemCatalogo;
import com.gs.monolito.pedidos.service.ExtractorSolicitud.Sugerencia;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lo que se puede sacar de un mail en castellano sin IA, y sobre todo lo que NO se inventa. Hoy es miércoles 21/10/2026.
 */
class ExtractorSolicitudTest {

    private static final LocalDate HOY = LocalDate.of(2026, 10, 21);
    private static final List<ItemCatalogo> CATALOGO = List.of(
            new ItemCatalogo(1L, "Corona"),
            new ItemCatalogo(2L, "Corona de Zirconio"),
            new ItemCatalogo(3L, "Prótesis Total Superior"),
            new ItemCatalogo(4L, "Férula Miorelajante"));

    private final ExtractorSolicitud extractor = new ExtractorSolicitud();

    private Sugerencia extraer(String asunto, String cuerpo) {
        return extractor.extraer(asunto, cuerpo, HOY, CATALOGO);
    }

    @Test
    void unMailCompletoSeLeeEntero() {
        Sugerencia s = extraer("Pedido corona",
                "Hola, les mando una corona de zirconio para la paciente María González, pieza 26, color A2. "
                        + "La necesito para el viernes 30/10, es urgente. Acordamos $85.000. Saludos, Dr. Pérez");

        assertThat(s.paciente()).isEqualTo("María González");
        assertThat(s.trabajo()).isEqualTo("Corona de Zirconio");   // el más específico, no "Corona"
        assertThat(s.catalogoTrabajoId()).isEqualTo(2L);
        assertThat(s.fechaEntrega()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(s.prioridad()).isEqualTo(Prioridad.URGENTE);
        assertThat(s.precio()).isEqualByComparingTo("85000");
        assertThat(s.observaciones()).contains("corona de zirconio");
    }

    @Test
    void unMailSinDatosNoInventaNada() {
        Sugerencia s = extraer("Consulta", "Buenas tardes, quería saber si trabajan con obra social. Gracias!");

        assertThat(s.paciente()).isNull();
        assertThat(s.trabajo()).isNull();
        assertThat(s.fechaEntrega()).isNull();
        assertThat(s.precio()).isNull();
        assertThat(s.prioridad()).isEqualTo(Prioridad.NORMAL);
    }

    // ── Fecha ────────────────────────────────────────────────────────

    @Test
    void unDiaDeLaSemanaEsElProximoQueViene() {
        assertThat(extraer("", "Lo necesito para el lunes").fechaEntrega()).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(extraer("", "Entrega el viernes por favor").fechaEntrega()).isEqualTo(LocalDate.of(2026, 10, 23));
        // hoy es miércoles: "para el miércoles" es el de la semana que viene, no hoy
        assertThat(extraer("", "Necesito para el miércoles").fechaEntrega()).isEqualTo(LocalDate.of(2026, 10, 28));
    }

    @Test
    void mananaYPasadoManana() {
        assertThat(extraer("", "Lo necesito para mañana").fechaEntrega()).isEqualTo(HOY.plusDays(1));
        assertThat(extraer("", "Lo necesito para pasado mañana").fechaEntrega()).isEqualTo(HOY.plusDays(2));
    }

    @Test
    void fechasExplicitasConYSinAnio() {
        assertThat(extraer("", "entrega 15/11/2026").fechaEntrega()).isEqualTo(LocalDate.of(2026, 11, 15));
        assertThat(extraer("", "para el 05-11-26").fechaEntrega()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(extraer("", "entrega 15.11.2026").fechaEntrega()).isEqualTo(LocalDate.of(2026, 11, 15));
        // sin año y ya pasada este año: la del año que viene
        assertThat(extraer("", "para el 5/10").fechaEntrega()).isEqualTo(LocalDate.of(2027, 10, 5));
    }

    @Test
    void unaFechaEscritaConElNombreDelMesSeLee() {
        assertThat(extraer("melina", "buenas quiero una corona de zirconio para el dia 4 de noviembre").fechaEntrega())
                .isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(extraer("", "Lo necesito para el 2 de setiembre de 2027").fechaEntrega()).isEqualTo(LocalDate.of(2027, 9, 2));
        assertThat(extraer("", "entrega 30 de octubre").fechaEntrega()).isEqualTo(LocalDate.of(2026, 10, 30));
        // sin año y ya pasada este año: la del año que viene
        assertThat(extraer("", "para el 5 de octubre").fechaEntrega()).isEqualTo(LocalDate.of(2027, 10, 5));
    }

    @Test
    void unaFechaConNombreDeMesSinPalabraDeEntregaNoSeLee() {
        assertThat(extraer("", "La paciente nació el 4 de noviembre").fechaEntrega()).isNull();
        assertThat(extraer("", "para el 31 de febrero").fechaEntrega()).isNull();
    }

    @Test
    void unaFechaImposibleOPasadaOUnaFraccionNoSonFechas() {
        assertThat(extraer("", "para el 31/02").fechaEntrega()).isNull();
        assertThat(extraer("", "entrega 01/01/2020").fechaEntrega()).isNull();
        assertThat(extraer("", "Necesito 3/4 de corona").fechaEntrega()).isNull();
    }

    // ── Paciente ─────────────────────────────────────────────────────

    @Test
    void elPacienteSeCortaAntesDeLasPalabrasQueYaNoSonSuNombre() {
        assertThat(extraer("", "Paciente: Juan Rodríguez").paciente()).isEqualTo("Juan Rodríguez");
        assertThat(extraer("", "pac. López Ana para el martes").paciente()).isEqualTo("López Ana");
        assertThat(extraer("", "paciente Pérez González, corona pieza 16").paciente()).isEqualTo("Pérez González");
        assertThat(extraer("", "px Gómez color A3").paciente()).isEqualTo("Gómez");
        assertThat(extraer("", "para la paciente María González. La necesito para el viernes").paciente()).isEqualTo("María González");
        assertThat(extraer("", "paciente M. Pérez, corona").paciente()).isEqualTo("M. Pérez");   // el punto de una inicial no corta
    }

    @Test
    void laPalabraPacientesEnOtroSentidoNoEsUnPaciente() {
        assertThat(extraer("", "Tenemos muchos pacientes de la clínica esperando").paciente()).isNull();
    }

    // ── Precio y urgencia ────────────────────────────────────────────

    @Test
    void elPrecioInterpretaMilesYIgnoraCentavos() {
        assertThat(extraer("", "$35.000").precio()).isEqualByComparingTo("35000");
        assertThat(extraer("", "$ 1.500,50").precio()).isEqualByComparingTo("1500");
        assertThat(extraer("", "$85000").precio()).isEqualByComparingTo("85000");
        assertThat(extraer("", "sale 85000 pesos").precio()).isNull();   // sin "$" no se adivina
    }

    @Test
    void laUrgenciaSeReconoceDeVariasFormas() {
        assertThat(extraer("URGENTE", "").prioridad()).isEqualTo(Prioridad.URGENTE);
        assertThat(extraer("", "Es una urgencia").prioridad()).isEqualTo(Prioridad.URGENTE);
        assertThat(extraer("", "lo antes posible").prioridad()).isEqualTo(Prioridad.URGENTE);
        assertThat(extraer("", "sin apuro").prioridad()).isEqualTo(Prioridad.NORMAL);
    }

    // ── Trabajo ──────────────────────────────────────────────────────

    @Test
    void elTrabajoSeEncuentraSinImportarTildesNiMayusculasNiElPlural() {
        assertThat(extraer("", "necesito PROTESIS TOTAL SUPERIOR para el paciente Gómez").trabajo()).isEqualTo("Prótesis Total Superior");
        assertThat(extraer("", "dos coronas para la semana que viene").trabajo()).isEqualTo("Corona");
        assertThat(extraer("", "una ferula miorelajante").catalogoTrabajoId()).isEqualTo(4L);
    }

    @Test
    void unaPalabraQueSoloContieneElNombreNoCuenta() {
        assertThat(extraer("", "Es una coronación, no un trabajo").trabajo()).isNull();
    }

    // ── Cuerpo ───────────────────────────────────────────────────────

    @Test
    void lasRespuestasCitadasNoContaminanLaLectura() {
        String cuerpo = "Gracias, confirmo la entrega.\n\n"
                + "El mié 21 oct 2026, Laboratorio GS escribió:\n"
                + "> Su trabajo urgente de $99.000 para el lunes\n"
                + "> paciente Fantasma";

        Sugerencia s = extraer("Re: tu pedido", cuerpo);

        assertThat(s.prioridad()).isEqualTo(Prioridad.NORMAL);
        assertThat(s.precio()).isNull();
        assertThat(s.paciente()).isNull();
        assertThat(s.observaciones()).isEqualTo("Gracias, confirmo la entrega.");
    }

    @Test
    void lasObservacionesSeAcotan() {
        Sugerencia s = extraer("", "texto largo ".repeat(300));

        assertThat(s.observaciones()).hasSize(900).endsWith("…");
    }

    @Test
    void unCuerpoVacioONuloNoRompeNada() {
        assertThat(extraer(null, null).observaciones()).isNull();
        assertThat(extraer("", "").paciente()).isNull();
    }
}
