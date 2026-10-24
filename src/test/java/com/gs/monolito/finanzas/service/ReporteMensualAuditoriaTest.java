package com.gs.monolito.finanzas.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.finanzas.model.ReporteMensual;
import com.gs.monolito.finanzas.repository.ReporteMensualRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Generar un reporte mensual deja rastro; regenerar uno ya archivado lo dice, porque el PDF anterior se pierde. */
@ExtendWith(MockitoExtension.class)
class ReporteMensualAuditoriaTest {

    @Mock private ReporteCajaPdfService pdfService;
    @Mock private FinanzasComprobanteStorageService storage;
    @Mock private ReporteMensualRepository repo;
    @Mock private AuditoriaService auditoria;

    @InjectMocks private ReporteMensualService service;

    private void pdfListo() {
        when(pdfService.resumenMensual(anyInt(), anyInt())).thenReturn(new byte[]{1});
        when(storage.subirReporte(any(), anyInt(), anyInt())).thenReturn("reportes/2026-09.pdf");
    }

    @Test
    void elReporteDeCierreDeMesQuedaMarcadoComoAutomatico() {
        pdfListo();
        when(repo.findByAnioAndMes(2026, 9)).thenReturn(Optional.empty());

        service.generar(2026, 9, true);

        ArgumentCaptor<String> detalle = ArgumentCaptor.forClass(String.class);
        verify(auditoria).registrar(any(), eq("REPORTE"), eq("Reporte mensual generado"), eq("Reporte 09/2026"), detalle.capture());
        assertThat(detalle.getValue()).startsWith("Automático");
    }

    @Test
    void regenerarUnReporteYaArchivadoAvisaQueReemplazaAlAnterior() {
        pdfListo();
        ReporteMensual yaArchivado = new ReporteMensual();
        yaArchivado.setId(4L);
        when(repo.findByAnioAndMes(2026, 9)).thenReturn(Optional.of(yaArchivado));

        service.generar(2026, 9, false);

        ArgumentCaptor<String> detalle = ArgumentCaptor.forClass(String.class);
        verify(auditoria).registrar(any(), eq("REPORTE"), eq("Reporte mensual regenerado"), eq("Reporte 09/2026"), detalle.capture());
        assertThat(detalle.getValue()).startsWith("Manual").contains("reemplazó");
    }
}
