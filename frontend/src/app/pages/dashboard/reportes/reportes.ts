import { Component, OnInit, inject, DestroyRef, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { forkJoin } from 'rxjs';
import { PedidosService, PedidoResponse } from '../../../services/pedidos.service';
import { FinanzasService, CuentaCorrienteOdontologoResponse, ReporteMensualResponse } from '../../../services/finanzas.service';
import { AuthService } from '../../../services/auth';
import { iniciarPolling } from '../../../shared/poll.util';

interface BarData  { mes: string; valor: number; }
interface LineData { mes: string; facturacion: number; }
interface DonutSeg { label: string; pct: number; color: string; dash: number; offset: number; }
interface TipoBar  { label: string; valor: number; color: string; }

const COLORS = ['#22c55e', '#06b6d4', '#a855f7', '#f59e0b', '#f43f5e', '#64748b'];
const MES_CORTOS = ['Ene','Feb','Mar','Abr','May','Jun','Jul','Ago','Sep','Oct','Nov','Dic'];

@Component({
  selector: 'app-reportes',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './reportes.html',
  styleUrls: ['./reportes.css'],
})
export class ReportesComponent implements OnInit {
  private pedidosService = inject(PedidosService);
  private finanzasService = inject(FinanzasService);
  private auth = inject(AuthService);
  private destroyRef = inject(DestroyRef);

  readonly esAdmin = this.auth.isAdmin();

  loading = true;
  error = '';
  generandoPdf = false;

  // ── Reportes mensuales archivados (solo ADMIN) ───────────────────────────
  reportesArchivados = signal<ReporteMensualResponse[]>([]);
  cargandoReportes   = signal(false);
  errorReportes      = signal('');
  generandoReporte   = signal(false);

  resumen: any = null;

  kpis: { label: string; value: string; sub: string; color: string; icon: string }[] = [];
  barData: BarData[] = [];
  lineData: LineData[] = [];
  donutData: DonutSeg[] = [];
  tipoData: TipoBar[] = [];
  morosos: CuentaCorrienteOdontologoResponse[] = [];

  ngOnInit(): void {
    this.cargar();
    iniciarPolling(() => this.cargar(true), this.destroyRef);
    if (this.esAdmin) this.cargarReportesArchivados();
  }

  // ── Reportes mensuales archivados ───────────────────────────────────────

  cargarReportesArchivados(): void {
    this.cargandoReportes.set(true);
    this.errorReportes.set('');
    this.finanzasService.listarReportesMensuales().subscribe({
      next: rs => { this.reportesArchivados.set(rs); this.cargandoReportes.set(false); },
      error: () => {
        this.errorReportes.set('No se pudieron cargar los reportes.');
        this.cargandoReportes.set(false);
      },
    });
  }

  generarReporteActual(): void {
    const now = new Date();
    this.generandoReporte.set(true);
    this.errorReportes.set('');
    this.finanzasService.generarReporteMensual(now.getFullYear(), now.getMonth() + 1).subscribe({
      next: () => { this.generandoReporte.set(false); this.cargarReportesArchivados(); },
      error: () => {
        this.errorReportes.set('No se pudo generar el reporte de este mes.');
        this.generandoReporte.set(false);
      },
    });
  }

  descargarReporteArchivado(r: ReporteMensualResponse): void {
    this.finanzasService.descargarReporteMensual(r.id).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        window.open(url, '_blank');
        setTimeout(() => URL.revokeObjectURL(url), 15000);
      },
      error: () => { this.errorReportes.set('No se pudo abrir el reporte.'); },
    });
  }

  formatFechaArchivo(iso: string): string {
    return new Date(iso).toLocaleDateString('es-AR', { day: '2-digit', month: '2-digit', year: 'numeric' });
  }

  private cargar(silencioso = false): void {
    if (silencioso && this.generandoPdf) return; // no cambiar los datos mientras se exporta el PDF
    if (!silencioso) this.loading = true;
    forkJoin({
      pedidos: this.pedidosService.listarTodos(),
      resumen: this.finanzasService.obtenerResumen(),
      morosos:  this.finanzasService.rankingMorosos(),
    }).subscribe({
      next: ({ pedidos, resumen, morosos }) => {
        this.calcular(pedidos, resumen, morosos);
        if (!silencioso) this.loading = false;
      },
      error: err => {
        if (!silencioso) {
          this.error = 'No se pudieron cargar los datos. ¿Los servicios están corriendo?';
          this.loading = false;
        }
        console.error(err);
      },
    });
  }

  private calcular(
    pedidos: PedidoResponse[],
    resumen: any,
    morosos: CuentaCorrienteOdontologoResponse[],
  ): void {
    this.resumen = resumen;
    const hoy = new Date();
    const mesActual  = hoy.getMonth();
    const anioActual = hoy.getFullYear();
    const prevMes  = mesActual === 0 ? 11 : mesActual - 1;
    const prevAnio = mesActual === 0 ? anioActual - 1 : anioActual;

    const pedidosMes = pedidos.filter(p => this.enMes(p.fechaCreacion, mesActual, anioActual));
    const pedidosPrev = pedidos.filter(p => this.enMes(p.fechaCreacion, prevMes, prevAnio));
    const pendientes = pedidos.filter(p => p.estado === 'LISTO');
    const deudaTotal = morosos.reduce((acc, m) => acc + m.totalDeuda, 0);
    const facturacionMes = pedidosMes.reduce((s, p) => s + (p.precioAcordado ?? 0), 0);
    const delta = pedidosMes.length - pedidosPrev.length;

    // ── KPIs ──────────────────────────────────────────────────────────────────
    this.kpis = [
      {
        label: 'Pedidos este mes',
        value: String(pedidosMes.length),
        sub: pedidosPrev.length > 0
          ? `${delta >= 0 ? '+' : ''}${delta} vs mes anterior`
          : `${pedidosPrev.length} el mes pasado`,
        color: 'green',
        icon: 'M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2',
      },
      {
        label: 'Facturación del mes',
        value: this.formatMoney(facturacionMes),
        sub: `${pedidosMes.length} pedido${pedidosMes.length !== 1 ? 's' : ''} (precio acordado)`,
        color: 'cyan',
        icon: 'M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z',
      },
      {
        label: 'Deuda total clientes',
        value: this.formatMoney(deudaTotal),
        sub: `${morosos.length} odontólogo${morosos.length !== 1 ? 's' : ''} con deuda`,
        color: 'amber',
        icon: 'M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z',
      },
      {
        label: 'Entregas pendientes',
        value: String(pendientes.length),
        sub: pendientes.length === 0 ? 'Todo entregado' : `${pendientes.length} listo${pendientes.length !== 1 ? 's' : ''} para retirar`,
        color: 'orange',
        icon: 'M5 8h14M5 8a2 2 0 110-4h14a2 2 0 110 4M5 8l1 12a2 2 0 002 2h8a2 2 0 002-2l1-12',
      },
    ];

    // ── Bar chart: pedidos por mes (últimos 6) ────────────────────────────────
    const meses6 = this.ultimos6Meses();
    this.barData = meses6.map(({ mes, mesIdx, anio }) => ({
      mes,
      valor: pedidos.filter(p => this.enMes(p.fechaCreacion, mesIdx, anio)).length,
    }));

    // ── Line chart: facturación mensual ───────────────────────────────────────
    this.lineData = meses6.map(({ mes, mesIdx, anio }) => ({
      mes,
      facturacion: pedidos
        .filter(p => this.enMes(p.fechaCreacion, mesIdx, anio))
        .reduce((s, p) => s + (p.precioAcordado ?? 0), 0),
    }));

    // ── Donut + barra horizontal: distribución de trabajos ────────────────────
    const conteo = new Map<string, number>();
    for (const p of pedidos) {
      const tipo = this.normalizarTipo(p.trabajo);
      conteo.set(tipo, (conteo.get(tipo) ?? 0) + 1);
    }
    const ordenados = [...conteo.entries()].sort((a, b) => b[1] - a[1]);
    const top5 = ordenados.slice(0, 5);
    const otrosCount = ordenados.slice(5).reduce((s, [, c]) => s + c, 0);
    if (otrosCount > 0) top5.push(['Otros', otrosCount]);

    const totalTipos = top5.reduce((s, [, c]) => s + c, 0) || 1;
    this.tipoData = top5.map(([label, valor], i) => ({
      label, valor, color: COLORS[i] ?? '#64748b',
    }));

    const circ = 2 * Math.PI * 60;
    let offsetAcc = 0;
    this.donutData = top5.map(([label, valor], i) => {
      const pct = Math.round((valor / totalTipos) * 100);
      const dash = (valor / totalTipos) * circ;
      const seg: DonutSeg = { label, pct, color: COLORS[i] ?? '#64748b', dash, offset: -offsetAcc };
      offsetAcc += dash;
      return seg;
    });

    this.morosos = morosos.slice(0, 5);
  }

  // ── Helpers de charts ──────────────────────────────────────────────────────

  get barMax(): number { return Math.max(...this.barData.map(d => d.valor), 1); }
  barHeight(v: number): number { return Math.round((v / this.barMax) * 140); }
  barY(v: number): number { return 160 - this.barHeight(v); }
  barX(i: number): number { return 30 + i * 72; }

  get lineMax(): number { return Math.max(...this.lineData.map(d => d.facturacion), 1); }
  linePoints(): string {
    return this.lineData.map((d, i) => {
      const x = 30 + i * 80;
      const y = 160 - Math.round((d.facturacion / this.lineMax) * 140);
      return `${x},${y}`;
    }).join(' ');
  }
  lineX(i: number): number { return 30 + i * 80; }
  lineY(val: number): number { return 160 - Math.round((val / this.lineMax) * 140); }

  get tipoMax(): number { return Math.max(...this.tipoData.map(d => d.valor), 1); }
  tipoWidth(v: number): number { return Math.round((v / this.tipoMax) * 100); }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private ultimos6Meses(): { mes: string; mesIdx: number; anio: number }[] {
    const resultado = [];
    const hoy = new Date();
    for (let i = 5; i >= 0; i--) {
      const d = new Date(hoy.getFullYear(), hoy.getMonth() - i, 1);
      resultado.push({ mes: MES_CORTOS[d.getMonth()], mesIdx: d.getMonth(), anio: d.getFullYear() });
    }
    return resultado;
  }

  private enMes(fechaIso: string, mes: number, anio: number): boolean {
    const d = new Date(fechaIso);
    return d.getMonth() === mes && d.getFullYear() === anio;
  }

  private normalizarTipo(trabajo: string): string {
    const t = trabajo.trim();
    if (/corona|crown|jacket|zircon/i.test(t)) return 'Corona / Zirconio';
    if (/prot[eé]sis/i.test(t))                return 'Prótesis';
    if (/ortod[eo]n/i.test(t))                 return 'Ortodoncia';
    if (/implant/i.test(t))                    return 'Implante';
    if (/atm|f[eé]rula/i.test(t))              return 'ATM / Férula';
    return t.length > 22 ? t.substring(0, 22) + '…' : t;
  }

  formatMoney(n: number): string {
    return new Intl.NumberFormat('es-AR', {
      style: 'currency', currency: 'ARS', maximumFractionDigits: 0,
    }).format(n);
  }

  /**
   * Igual que formatMoney pero seguro para jsPDF: la fuente Helvetica (WinAnsi) no
   * puede codificar el signo menos Unicode (−, U+2212) ni los espacios especiales
   * (nbsp / narrow-nbsp) que agrega Intl, y rompía el renderizado de toda la línea.
   */
  private pdfMoney(n: number): string {
    return this.formatMoney(n).replace(/−/g, '-').replace(/[  ]/g, ' ');
  }

  formatMoneyShort(n: number): string {
    if (n >= 1_000_000) return '$' + (n / 1_000_000).toFixed(1) + 'M';
    if (n >= 1_000)     return '$' + Math.round(n / 1_000) + 'K';
    return this.formatMoney(n);
  }

  async exportarResumenMensual(): Promise<void> {
    this.generandoPdf = true;
    try {
      const { jsPDF } = await import('jspdf');
      const doc = new jsPDF({ unit: 'mm', format: 'a4' });
      const W = 210, MARGIN = 14, CW = W - MARGIN * 2;
      const hoy = new Date();
      const MES = ['Enero','Febrero','Marzo','Abril','Mayo','Junio','Julio','Agosto','Septiembre','Octubre','Noviembre','Diciembre'];
      const mesLabel = MES[hoy.getMonth()] + ' ' + hoy.getFullYear();

      // Header bar
      doc.setFillColor(124, 58, 237);
      doc.rect(0, 0, W, 12, 'F');
      doc.setFontSize(9);
      doc.setTextColor(255, 255, 255);
      doc.text('LABORATORIO G&S', MARGIN, 8);
      doc.text('Resumen Mensual', W - MARGIN, 8, { align: 'right' as any });

      // Titulo
      let y = 22;
      doc.setFontSize(18);
      doc.setTextColor(20, 30, 48);
      doc.text('Resumen Mensual', MARGIN, y);
      doc.setFontSize(9);
      doc.setTextColor(100, 110, 130);
      doc.text(mesLabel, MARGIN, y + 7);
      y += 17;
      doc.setDrawColor(220, 225, 235);
      doc.line(MARGIN, y, W - MARGIN, y);
      y += 8;

      // KPIs
      doc.setFontSize(8);
      doc.setTextColor(80, 90, 110);
      doc.text('INDICADORES DEL MES', MARGIN, y);
      y += 7;
      for (const k of this.kpis) {
        doc.setFontSize(9);
        doc.setTextColor(40, 50, 65);
        doc.text(k.label, MARGIN + 3, y);
        doc.setFontSize(11);
        doc.setTextColor(20, 30, 48);
        doc.text(k.value, W - MARGIN, y, { align: 'right' as any });
        y += 5;
        doc.setFontSize(7.5);
        doc.setTextColor(120, 130, 150);
        doc.text(k.sub, W - MARGIN, y, { align: 'right' as any });
        doc.setDrawColor(235, 237, 242);
        doc.line(MARGIN, y + 2, W - MARGIN, y + 2);
        y += 8;
      }
      y += 4;

      // Top morosos
      if (this.morosos.length > 0) {
        doc.setFontSize(8);
        doc.setTextColor(80, 90, 110);
        doc.text('TOP DEUDORES', MARGIN, y);
        y += 6;
        doc.setFillColor(242, 244, 248);
        doc.rect(MARGIN, y - 3, CW, 8, 'F');
        doc.setFontSize(7.5);
        doc.setTextColor(90, 100, 120);
        doc.text('ODONTOLOGO', MARGIN + 2, y + 2);
        doc.text('COMP.', MARGIN + 105, y + 2);
        doc.text('DIAS', MARGIN + 130, y + 2);
        doc.text('DEUDA TOTAL', W - MARGIN, y + 2, { align: 'right' as any });
        y += 8;
        for (const m of this.morosos) {
          if (y > 270) { doc.addPage(); y = 20; }
          doc.setFontSize(9);
          doc.setTextColor(40, 50, 65);
          doc.text(m.odontologoNombre, MARGIN + 2, y + 4);
          doc.setTextColor(100, 110, 130);
          doc.text(String(m.comprobantesPendientes), MARGIN + 105, y + 4);
          doc.text(m.diasSinPagar + 'd', MARGIN + 130, y + 4);
          doc.setTextColor(220, 38, 38);
          doc.text(this.pdfMoney(m.totalDeuda), W - MARGIN, y + 4, { align: 'right' as any });
          doc.setDrawColor(235, 237, 242);
          doc.line(MARGIN, y + 7, W - MARGIN, y + 7);
          y += 9;
        }
      }

      // Sección financiera
      y += 6;
      doc.setFontSize(8);
      doc.setTextColor(80, 90, 110);
      doc.text('ESTADO DE CAJA Y OBLIGACIONES', MARGIN, y);
      y += 6;

      const r = this.resumen ?? {};
      const saldoFisica       = r.saldoFisica        ?? 0;
      const saldoBancaria     = r.saldoBancaria       ?? 0;
      const saldoCompensacion = r.saldoCompensacion   ?? 0;
      const totalCajas        = saldoFisica + saldoBancaria + saldoCompensacion;
      const sueldosPend       = r.totalSueldosPendientes ?? 0;
      const deudaProv         = r.totalDeudaProveedores  ?? 0;

      const finFilas = [
        { label: 'Caja física',          valor: saldoFisica,       color: [40,  50,  65] as [number,number,number] },
        { label: 'Caja bancaria',         valor: saldoBancaria,     color: [40,  50,  65] as [number,number,number] },
        { label: 'Caja compensación',     valor: saldoCompensacion, color: [40,  50,  65] as [number,number,number] },
        { label: 'Total disponible',      valor: totalCajas,        color: [22, 101,  52] as [number,number,number] },
        { label: 'Sueldos pendientes',    valor: -sueldosPend,      color: [185,  28,  28] as [number,number,number] },
        { label: 'Deuda proveedores',     valor: -deudaProv,        color: [185,  28,  28] as [number,number,number] },
        { label: 'Balance proyectado',    valor: totalCajas - sueldosPend - deudaProv,
          color: (totalCajas - sueldosPend - deudaProv) >= 0 ? [22,101,52] as [number,number,number] : [185,28,28] as [number,number,number] },
      ];

      for (const fila of finFilas) {
        if (y > 270) { doc.addPage(); y = 20; }
        const isSeparator = fila.label === 'Total disponible' || fila.label === 'Balance proyectado';
        if (isSeparator) {
          doc.setDrawColor(200, 205, 215);
          doc.line(MARGIN, y - 1, W - MARGIN, y - 1);
        }
        doc.setFontSize(isSeparator ? 9.5 : 9);
        doc.setTextColor(...fila.color);
        doc.text(fila.label, MARGIN + 3, y + 3);
        const montoStr = (fila.valor < 0 ? '-' : '') + this.pdfMoney(Math.abs(fila.valor));
        doc.text(montoStr, W - MARGIN, y + 3, { align: 'right' as any });
        doc.setDrawColor(235, 237, 242);
        if (!isSeparator) doc.line(MARGIN, y + 6, W - MARGIN, y + 6);
        y += isSeparator ? 10 : 8;
      }

      // Footer
      y += 4;
      doc.setFontSize(7);
      doc.setTextColor(160, 170, 185);
      doc.text('Generado: ' + hoy.toLocaleString('es-AR') + ' | Sistema ERP Laboratorio G&S', MARGIN, 288);

      doc.save('resumen-mensual-' + hoy.getFullYear() + '-' + String(hoy.getMonth() + 1).padStart(2, '0') + '.pdf');
    } finally {
      this.generandoPdf = false;
    }
  }
}
