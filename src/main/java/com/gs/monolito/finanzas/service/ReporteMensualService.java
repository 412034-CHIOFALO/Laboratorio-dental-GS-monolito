package com.gs.monolito.finanzas.service;

import com.gs.monolito.finanzas.dto.ReporteMensualResponse;
import com.gs.monolito.finanzas.exception.BusinessException;
import com.gs.monolito.finanzas.model.ReporteMensual;
import com.gs.monolito.finanzas.repository.ReporteMensualRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/**
 * Genera, archiva y lista los reportes financieros mensuales. El PDF se
 * produce con {@link ReporteCajaPdfService} y se guarda en MinIO; los
 * metadatos quedan en {@code reporte_mensual} (uno por mes, se sobrescribe
 * al regenerar).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReporteMensualService {

    private final ReporteCajaPdfService pdfService;
    private final FinanzasComprobanteStorageService storage;
    private final ReporteMensualRepository repo;

    @Transactional
    public ReporteMensualResponse generar(int anio, int mes, boolean automatico) {
        if (mes < 1 || mes > 12) {
            throw new BusinessException("El mes debe estar entre 1 y 12.");
        }

        byte[] pdf = pdfService.resumenMensual(anio, mes);
        String nombre = String.format("resumen-mensual-%d-%02d.pdf", anio, mes);
        String objectName = storage.subirReporte(pdf, anio, mes);
        if (objectName == null) {
            throw new BusinessException("No se pudo archivar el reporte: el almacenamiento no está disponible.");
        }

        ReporteMensual r = repo.findByAnioAndMes(anio, mes).orElseGet(ReporteMensual::new);
        r.setAnio(anio);
        r.setMes(mes);
        r.setObjectName(objectName);
        r.setNombreArchivo(nombre);
        r.setGeneradoEn(LocalDateTime.now());
        r.setAutomatico(automatico);
        repo.save(r);

        log.info("[REPORTE] Reporte mensual {}/{} generado ({}).", mes, anio, automatico ? "automatico" : "manual");
        return ReporteMensualResponse.from(r);
    }

    /** Genera el reporte del mes anterior al actual (lo usa la tarea programada). */
    @Transactional
    public void generarMesAnterior() {
        YearMonth anterior = YearMonth.now().minusMonths(1);
        generar(anterior.getYear(), anterior.getMonthValue(), true);
    }

    @Transactional(readOnly = true)
    public List<ReporteMensualResponse> listar() {
        return repo.findAllByOrderByAnioDescMesDesc().stream()
            .map(ReporteMensualResponse::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public String objectKey(Long id) {
        ReporteMensual r = repo.findById(id)
            .orElseThrow(() -> new BusinessException("El reporte solicitado no existe."));
        return r.getObjectName();
    }
}
