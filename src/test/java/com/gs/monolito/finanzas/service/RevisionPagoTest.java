package com.gs.monolito.finanzas.service;

import com.gs.monolito.finanzas.dto.PagoAutomaticoRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El semáforo de comprobantes: una transferencia solo se registra sola si está "limpia"; todo lo demás
 * pasa a revisión de una persona. Estas pruebas fijan QUÉ cuenta como sospechoso.
 */
class RevisionPagoTest {

    private static final LocalDate HOY = LocalDate.of(2026, 10, 12);
    private final RevisionPago semaforo = new RevisionPago(true, new BigDecimal("100000"), 7,
            Clock.fixed(Instant.parse("2026-10-12T15:00:00Z"), ZoneOffset.UTC));

    /** Una transferencia perfecta: todo leído con confianza, nombres que coinciden, fecha de ayer, monto bajo. */
    private PagoAutomaticoRequest limpia() {
        PagoAutomaticoRequest r = new PagoAutomaticoRequest();
        r.setReceptorNombre("Carlos López");
        r.setEmisor("Dr. García");
        r.setMonto(new BigDecimal("85000"));
        r.setIdOperacion("98765432101");
        r.setMontoOrigen("COMPROBANTE_ALTA");
        r.setFechaComprobante(HOY.minusDays(1));
        r.setNombresCoinciden(true);
        return r;
    }

    @Test
    void unaTransferenciaLimpiaSeRegistraSola() {
        assertThat(semaforo.motivos(limpia())).isEmpty();
    }

    @Test
    void nombresQueNoCoincidenVanARevision() {
        PagoAutomaticoRequest r = limpia();
        r.setNombresCoinciden(false);
        r.setDetalleNombres("receptor \"Pedro Gómez\" ≠ comprobante \"Carlos López\"");
        assertThat(semaforo.motivos(r)).singleElement().asString()
                .contains("no coinciden").contains("Pedro Gómez");
    }

    @Test
    void siElComprobanteNoTraeNombresTambienVaARevision() {
        PagoAutomaticoRequest r = limpia();
        r.setNombresCoinciden(null);
        assertThat(semaforo.motivos(r)).singleElement().asString().contains("no se pudieron comparar");
    }

    @Test
    void sinNumeroDeOperacionVaARevision() {
        PagoAutomaticoRequest r = limpia();
        r.setIdOperacion("  ");
        assertThat(semaforo.motivos(r)).singleElement().asString().contains("N° de operación");
    }

    @Test
    void soloElMontoLeidoConConfianzaAltaSeDaPorBueno() {
        for (String origen : List.of("MANUAL", "COMPROBANTE_MEDIA", "COMPROBANTE_BAJA")) {
            PagoAutomaticoRequest r = limpia();
            r.setMontoOrigen(origen);
            assertThat(semaforo.motivos(r)).as(origen).hasSize(1);
        }
        PagoAutomaticoRequest sinDato = limpia();
        sinDato.setMontoOrigen(null);
        assertThat(semaforo.motivos(sinDato)).singleElement().asString().contains("No se informó");
    }

    @Test
    void laFechaSeValidaContraHoy() {
        PagoAutomaticoRequest sinFecha = limpia();
        sinFecha.setFechaComprobante(null);
        assertThat(semaforo.motivos(sinFecha)).singleElement().asString().contains("No se pudo leer la fecha");

        PagoAutomaticoRequest justoEnElLimite = limpia();
        justoEnElLimite.setFechaComprobante(HOY.minusDays(7));
        assertThat(semaforo.motivos(justoEnElLimite)).isEmpty();

        PagoAutomaticoRequest vieja = limpia();
        vieja.setFechaComprobante(HOY.minusDays(8));
        assertThat(semaforo.motivos(vieja)).singleElement().asString().contains("8 días de antigüedad");

        PagoAutomaticoRequest futura = limpia();
        futura.setFechaComprobante(HOY.plusDays(3));
        assertThat(semaforo.motivos(futura)).singleElement().asString().contains("futura");
    }

    @Test
    void desdeElTopeDeMontoSiempreSeRevisa() {
        PagoAutomaticoRequest justoDebajo = limpia();
        justoDebajo.setMonto(new BigDecimal("99999"));
        assertThat(semaforo.motivos(justoDebajo)).isEmpty();

        PagoAutomaticoRequest enElTope = limpia();
        enElTope.setMonto(new BigDecimal("100000"));
        assertThat(semaforo.motivos(enElTope)).singleElement().asString().contains("Monto alto");
    }

    @Test
    void losRastrosDeEdicionVanARevision() {
        PagoAutomaticoRequest r = limpia();
        r.setSenalesArchivo("el archivo se generó o editó con photoshop");
        assertThat(semaforo.motivos(r)).singleElement().asString().contains("Rastros de edición").contains("photoshop");
    }

    @Test
    void variosProblemasSeInformanTodosJuntos() {
        PagoAutomaticoRequest r = limpia();
        r.setIdOperacion(null);
        r.setNombresCoinciden(false);
        r.setFechaComprobante(HOY.minusDays(40));
        r.setMonto(new BigDecimal("500000"));
        assertThat(semaforo.motivos(r)).hasSize(4);
    }

    @Test
    void elDetalleDeLaLecturaExplicaLosMotivosPeroNoLosCrea() {
        PagoAutomaticoRequest limpiaConDetalle = limpia();
        limpiaConDetalle.setDetalleLectura("el pie del mensaje dice $85.000 y el comprobante $65.000");
        assertThat(semaforo.motivos(limpiaConDetalle)).as("sin motivos, el detalle solo no frena nada").isEmpty();

        PagoAutomaticoRequest dudosa = limpia();
        dudosa.setMontoOrigen("COMPROBANTE_MEDIA");
        dudosa.setDetalleLectura("el pie del mensaje dice $85.000 y el comprobante $65.000");
        assertThat(semaforo.motivos(dudosa)).hasSize(2)
                .anyMatch(x -> x.contains("poca confianza"))
                .anyMatch(x -> x.contains("Cómo se leyó el comprobante") && x.contains("$65.000"));
    }

    @Test
    void conElSemaforoApagadoNadaVaARevision() {
        RevisionPago apagado = new RevisionPago(false, new BigDecimal("100000"), 7, Clock.systemUTC());
        PagoAutomaticoRequest r = new PagoAutomaticoRequest();   // peor caso: no trae ninguna señal
        r.setMonto(new BigDecimal("9999999"));
        assertThat(apagado.motivos(r)).isEmpty();
    }
}
