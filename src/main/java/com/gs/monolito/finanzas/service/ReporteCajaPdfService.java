package com.gs.monolito.finanzas.service;

import com.gs.monolito.finanzas.dto.CajaMovimientoResponse;
import com.gs.monolito.finanzas.dto.ResumenCajasResponse;
import com.gs.monolito.finanzas.model.TipoCaja;
import com.gs.monolito.finanzas.model.TipoMovimientoCaja;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Genera los reportes de caja en PDF (OpenPDF): cierre diario y resumen
 * mensual. Los datos se toman de {@link ICajaService} (misma fuente que el
 * panel web).
 */
@Service
@RequiredArgsConstructor
public class ReporteCajaPdfService {

    private final ICajaService cajaService;

    private static final Locale AR = Locale.forLanguageTag("es-AR");
    private static final DateTimeFormatter F_FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter F_TS = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final Color TEAL   = new Color(15, 118, 110);
    private static final Color GRIS   = new Color(100, 116, 139);
    private static final Color VERDE  = new Color(21, 128, 61);
    private static final Color ROJO   = new Color(185, 28, 28);
    private static final Color FILA   = new Color(241, 245, 249);

    private static final Font F_TITULO   = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, TEAL);
    private static final Font F_SUBT     = FontFactory.getFont(FontFactory.HELVETICA, 10, GRIS);
    private static final Font F_SECCION  = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Color.DARK_GRAY);
    private static final Font F_TH       = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);
    private static final Font F_TD       = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.DARK_GRAY);
    private static final Font F_TD_B     = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.DARK_GRAY);
    private static final Font F_FOOT     = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, GRIS);

    // ───────────────────────────────── Cierre diario ─────────────────────────────────

    public byte[] cierreDiario(LocalDate fecha) {
        List<CajaMovimientoResponse> movs = cajaService.listarMovimientosByPeriodo(fecha, fecha);
        ResumenCajasResponse resumen = cajaService.obtenerResumen();

        return build(doc -> {
            encabezado(doc, "Cierre de Caja Diario", "Movimientos del " + fecha.format(F_FECHA));

            BigDecimal ingDia = BigDecimal.ZERO, egrDia = BigDecimal.ZERO;
            for (TipoCaja caja : TipoCaja.values()) {
                List<CajaMovimientoResponse> deCaja = movs.stream()
                    .filter(m -> m.tipoCaja() == caja)
                    .sorted(Comparator.comparing(CajaMovimientoResponse::id))
                    .toList();
                if (deCaja.isEmpty()) continue;

                doc.add(seccion("Caja " + nombreCaja(caja)));
                PdfPTable t = tablaMovimientos();
                BigDecimal ing = BigDecimal.ZERO, egr = BigDecimal.ZERO;
                for (CajaMovimientoResponse m : deCaja) {
                    filaMovimiento(t, m.concepto(), m.tipo(), m.monto(), m.creadoPor());
                    if (m.tipo() == TipoMovimientoCaja.INGRESO) ing = ing.add(m.monto());
                    else egr = egr.add(m.monto());
                }
                subtotal(t, ing, egr);
                doc.add(t);
                ingDia = ingDia.add(ing);
                egrDia = egrDia.add(egr);
            }

            if (movs.isEmpty()) {
                Paragraph p = new Paragraph("No se registraron movimientos en esta fecha.", F_TD);
                p.setSpacingBefore(8);
                doc.add(p);
            } else {
                doc.add(totalesDia(ingDia, egrDia));
            }

            doc.add(seccion("Saldos actuales de las cajas"));
            doc.add(tablaSaldos(resumen));
        });
    }

    // ──────────────────────────────── Resumen mensual ────────────────────────────────

    public byte[] resumenMensual(int anio, int mes) {
        YearMonth ym = YearMonth.of(anio, mes);
        LocalDate desde = ym.atDay(1);
        LocalDate hasta = ym.atEndOfMonth();
        List<CajaMovimientoResponse> movs = cajaService.listarMovimientosByPeriodo(desde, hasta);
        ResumenCajasResponse resumen = cajaService.obtenerResumen();

        return build(doc -> {
            String titulo = ym.getMonth().getDisplayName(java.time.format.TextStyle.FULL, AR);
            titulo = Character.toUpperCase(titulo.charAt(0)) + titulo.substring(1) + " " + anio;
            encabezado(doc, "Resumen Mensual de Caja", titulo);

            doc.add(seccion("Totales por caja"));
            PdfPTable resu = new PdfPTable(new float[]{3, 2, 2, 2});
            resu.setWidthPercentage(100);
            resu.setSpacingBefore(4);
            th(resu, "Caja", Element.ALIGN_LEFT);
            th(resu, "Ingresos", Element.ALIGN_RIGHT);
            th(resu, "Egresos", Element.ALIGN_RIGHT);
            th(resu, "Neto", Element.ALIGN_RIGHT);
            BigDecimal totIng = BigDecimal.ZERO, totEgr = BigDecimal.ZERO;
            boolean alterna = false;
            for (TipoCaja caja : TipoCaja.values()) {
                BigDecimal ing = sumar(movs, caja, TipoMovimientoCaja.INGRESO);
                BigDecimal egr = sumar(movs, caja, TipoMovimientoCaja.EGRESO);
                Color bg = alterna ? FILA : Color.WHITE; alterna = !alterna;
                td(resu, "Caja " + nombreCaja(caja), Element.ALIGN_LEFT, F_TD_B, bg);
                td(resu, money(ing), Element.ALIGN_RIGHT, F_TD, bg);
                td(resu, money(egr), Element.ALIGN_RIGHT, F_TD, bg);
                tdColor(resu, money(ing.subtract(egr)), ing.subtract(egr));
                totIng = totIng.add(ing);
                totEgr = totEgr.add(egr);
            }
            td(resu, "TOTAL", Element.ALIGN_LEFT, F_TD_B, FILA);
            td(resu, money(totIng), Element.ALIGN_RIGHT, F_TD_B, FILA);
            td(resu, money(totEgr), Element.ALIGN_RIGHT, F_TD_B, FILA);
            tdColor(resu, money(totIng.subtract(totEgr)), totIng.subtract(totEgr));
            doc.add(resu);

            doc.add(seccion("Detalle de movimientos"));
            if (movs.isEmpty()) {
                doc.add(new Paragraph("No hubo movimientos en el mes.", F_TD));
            } else {
                PdfPTable det = new PdfPTable(new float[]{1.4f, 1.6f, 3, 1.2f, 1.8f});
                det.setWidthPercentage(100);
                det.setSpacingBefore(4);
                th(det, "Fecha", Element.ALIGN_LEFT);
                th(det, "Caja", Element.ALIGN_LEFT);
                th(det, "Concepto", Element.ALIGN_LEFT);
                th(det, "Tipo", Element.ALIGN_CENTER);
                th(det, "Monto", Element.ALIGN_RIGHT);
                boolean alt = false;
                List<CajaMovimientoResponse> ordenados = movs.stream()
                    .sorted(Comparator.comparing(CajaMovimientoResponse::fechaMovimiento)
                        .thenComparing(CajaMovimientoResponse::id))
                    .toList();
                for (CajaMovimientoResponse m : ordenados) {
                    Color bg = alt ? FILA : Color.WHITE; alt = !alt;
                    boolean ing = m.tipo() == TipoMovimientoCaja.INGRESO;
                    td(det, m.fechaMovimiento().format(F_FECHA), Element.ALIGN_LEFT, F_TD, bg);
                    td(det, nombreCaja(m.tipoCaja()), Element.ALIGN_LEFT, F_TD, bg);
                    td(det, m.concepto() == null ? "" : m.concepto(), Element.ALIGN_LEFT, F_TD, bg);
                    td(det, ing ? "Ingreso" : "Egreso", Element.ALIGN_CENTER,
                        FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, ing ? VERDE : ROJO), bg);
                    td(det, (ing ? "+ " : "- ") + money(m.monto()), Element.ALIGN_RIGHT, F_TD, bg);
                }
                doc.add(det);
            }

            doc.add(seccion("Situación al día de hoy"));
            doc.add(tablaSaldos(resumen));
        });
    }

    // ───────────────────────────────── Helpers de layout ─────────────────────────────────

    @FunctionalInterface
    private interface Contenido { void render(Document doc) throws DocumentException; }

    private byte[] build(Contenido contenido) {
        Document doc = new Document(PageSize.A4, 40, 40, 46, 40);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();
            contenido.render(doc);
            Paragraph foot = new Paragraph(
                "Generado automáticamente por el sistema · " + java.time.LocalDateTime.now().format(F_TS), F_FOOT);
            foot.setSpacingBefore(18);
            foot.setAlignment(Element.ALIGN_CENTER);
            doc.add(foot);
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo generar el PDF: " + e.getMessage(), e);
        }
    }

    private void encabezado(Document doc, String titulo, String subtitulo) throws DocumentException {
        Paragraph lab = new Paragraph("Laboratorio GS · GS Ortodoncia",
            FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Color.DARK_GRAY));
        doc.add(lab);
        Paragraph tec = new Paragraph("Rebeca González — Matrícula 1666 · Córdoba", F_SUBT);
        tec.setSpacingAfter(10);
        doc.add(tec);

        Paragraph t = new Paragraph(titulo, F_TITULO);
        doc.add(t);
        Paragraph s = new Paragraph(subtitulo, F_SUBT);
        s.setSpacingAfter(6);
        doc.add(s);

        PdfPTable linea = new PdfPTable(1);
        linea.setWidthPercentage(100);
        PdfPCell c = new PdfPCell();
        c.setFixedHeight(2f);
        c.setBackgroundColor(TEAL);
        c.setBorder(0);
        linea.addCell(c);
        linea.setSpacingAfter(6);
        doc.add(linea);
    }

    private Paragraph seccion(String texto) {
        Paragraph p = new Paragraph(texto, F_SECCION);
        p.setSpacingBefore(14);
        p.setSpacingAfter(2);
        return p;
    }

    private PdfPTable tablaMovimientos() {
        PdfPTable t = new PdfPTable(new float[]{4, 1.4f, 2, 2});
        t.setWidthPercentage(100);
        t.setSpacingBefore(4);
        th(t, "Concepto", Element.ALIGN_LEFT);
        th(t, "Tipo", Element.ALIGN_CENTER);
        th(t, "Cargado por", Element.ALIGN_LEFT);
        th(t, "Monto", Element.ALIGN_RIGHT);
        return t;
    }

    private void filaMovimiento(PdfPTable t, String concepto, TipoMovimientoCaja tipo, BigDecimal monto, String creadoPor) {
        boolean ing = tipo == TipoMovimientoCaja.INGRESO;
        td(t, concepto == null ? "" : concepto, Element.ALIGN_LEFT, F_TD, Color.WHITE);
        td(t, ing ? "Ingreso" : "Egreso", Element.ALIGN_CENTER,
            FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, ing ? VERDE : ROJO), Color.WHITE);
        td(t, creadoPor == null ? "—" : creadoPor, Element.ALIGN_LEFT, F_TD, Color.WHITE);
        td(t, (ing ? "+ " : "- ") + money(monto), Element.ALIGN_RIGHT, F_TD, Color.WHITE);
    }

    private void subtotal(PdfPTable t, BigDecimal ing, BigDecimal egr) {
        PdfPCell c = new PdfPCell(new Phrase("Subtotal — Ingresos " + money(ing) + "  ·  Egresos " + money(egr)
            + "  ·  Neto " + money(ing.subtract(egr)), F_TD_B));
        c.setColspan(4);
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        c.setBackgroundColor(FILA);
        c.setPadding(5);
        t.addCell(c);
    }

    private Paragraph totalesDia(BigDecimal ing, BigDecimal egr) {
        BigDecimal neto = ing.subtract(egr);
        Paragraph p = new Paragraph();
        p.setSpacingBefore(10);
        p.add(new Phrase("Total del día — ", F_SECCION));
        p.add(new Phrase("Ingresos " + money(ing) + "   ", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, VERDE)));
        p.add(new Phrase("Egresos " + money(egr) + "   ", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, ROJO)));
        p.add(new Phrase("Neto " + money(neto),
            FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, neto.signum() < 0 ? ROJO : VERDE)));
        return p;
    }

    private PdfPTable tablaSaldos(ResumenCajasResponse r) {
        PdfPTable t = new PdfPTable(new float[]{3, 2});
        t.setWidthPercentage(60);
        t.setHorizontalAlignment(Element.ALIGN_LEFT);
        t.setSpacingBefore(4);
        filaSaldo(t, "Caja Física", r.saldoFisica());
        filaSaldo(t, "Caja Bancaria", r.saldoBancaria());
        filaSaldo(t, "Caja Compensación", r.saldoCompensacion());
        filaSaldo(t, "Deuda a proveedores", r.totalDeudaProveedores());
        filaSaldo(t, "Sueldos pendientes", r.totalSueldosPendientes());
        return t;
    }

    private void filaSaldo(PdfPTable t, String label, BigDecimal valor) {
        td(t, label, Element.ALIGN_LEFT, F_TD_B, Color.WHITE);
        tdColor(t, money(valor), valor == null ? BigDecimal.ZERO : valor);
    }

    // ───────────────────────────────── Helpers de celdas ─────────────────────────────────

    private void th(PdfPTable t, String texto, int align) {
        PdfPCell c = new PdfPCell(new Phrase(texto, F_TH));
        c.setHorizontalAlignment(align);
        c.setBackgroundColor(TEAL);
        c.setPadding(5);
        c.setBorderColor(Color.WHITE);
        t.addCell(c);
    }

    private void td(PdfPTable t, String texto, int align, Font font, Color bg) {
        PdfPCell c = new PdfPCell(new Phrase(texto, font));
        c.setHorizontalAlignment(align);
        c.setBackgroundColor(bg);
        c.setPadding(4);
        c.setBorderColor(new Color(226, 232, 240));
        t.addCell(c);
    }

    private void tdColor(PdfPTable t, String texto, BigDecimal valor) {
        Color col = valor.signum() < 0 ? ROJO : (valor.signum() > 0 ? VERDE : Color.DARK_GRAY);
        td(t, texto, Element.ALIGN_RIGHT, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, col), Color.WHITE);
    }

    // ───────────────────────────────── Utilidades ─────────────────────────────────

    private BigDecimal sumar(List<CajaMovimientoResponse> movs, TipoCaja caja, TipoMovimientoCaja tipo) {
        return movs.stream()
            .filter(m -> m.tipoCaja() == caja && m.tipo() == tipo)
            .map(CajaMovimientoResponse::monto)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String nombreCaja(TipoCaja c) {
        return switch (c) {
            case FISICA -> "Física";
            case BANCARIA -> "Bancaria";
            case COMPENSACION -> "Compensación";
        };
    }

    private String money(BigDecimal v) {
        if (v == null) v = BigDecimal.ZERO;
        NumberFormat nf = NumberFormat.getNumberInstance(AR);
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return "$ " + nf.format(v);
    }
}
