import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { CajaMovimientoResponse, ResumenCajasResponse, TipoCaja } from '../../../services/finanzas.service';
import { descargarCsv } from '../../../shared/csv.util';
import { Mes, Rango, calcularCaja, enRango, parsearFecha } from './reportes.calculos';
import { dinero, fecha, pluralizar } from './reportes.formato';
import { GrupoBarras, RepBarrasComponent, SerieBarras } from './rep-graficos';

const NOMBRE_CAJA: Record<TipoCaja, string> = {
  FISICA: 'Caja física (efectivo)',
  BANCARIA: 'Caja bancaria',
  COMPENSACION: 'Caja de compensación',
};

/** Lo que entró y salió de las cajas, y cómo está parado el laboratorio hoy contra lo que debe pagar. */
@Component({
  selector: 'app-reportes-caja',
  imports: [RepBarrasComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './rep-panel.css',
  template: `
    @if (!movimientos()) {
      <p class="rep-aviso" role="alert">No se pudieron cargar los movimientos de caja, así que este reporte no se puede calcular ahora. Probá de nuevo en un momento.</p>
    }

    <div class="rep-barra">
      <p class="rep-barra-texto">{{ etiquetaPeriodo() }}. Se cuentan la caja física y la bancaria; la de compensación no, porque ahí cada triangulado anota un ingreso y un egreso iguales. Se consultan como máximo los últimos 12 meses.</p>
      <button type="button" class="rep-btn" [disabled]="delPeriodo().length === 0" (click)="exportar()">Exportar a Excel (CSV)</button>
    </div>

    <div class="rep-kpis">
      <div class="rep-kpi ok">
        <span class="rep-kpi-valor">{{ dinero(res().ingresos) }}</span>
        <span class="rep-kpi-etiqueta">Entró</span>
        <span class="rep-kpi-sub">Cobros y otros ingresos del período</span>
      </div>
      <div class="rep-kpi">
        <span class="rep-kpi-valor">{{ dinero(res().egresos) }}</span>
        <span class="rep-kpi-etiqueta">Salió</span>
        <span class="rep-kpi-sub">Sueldos, proveedores y gastos del período</span>
      </div>
      <div class="rep-kpi" [class]="res().neto >= 0 ? 'ok' : 'malo'">
        <span class="rep-kpi-valor">{{ dinero(res().neto) }}</span>
        <span class="rep-kpi-etiqueta">Resultado del período</span>
        <span class="rep-kpi-sub">Lo que entró menos lo que salió</span>
      </div>
    </div>

    @if (resumen(); as r) {
      <section class="rep-card">
        <h3>Cómo está parado el laboratorio hoy</h3>
        <p class="rep-ayuda">Lo que hay en las cajas contra lo que hay que pagar.</p>
        <div class="rep-tabla">
          <table>
            <tbody>
              <tr><td class="col-principal">Caja física</td><td class="num col-valor">{{ dinero(r.saldoFisica) }}</td></tr>
              <tr><td class="col-principal">Caja bancaria</td><td class="num col-valor">{{ dinero(r.saldoBancaria) }}</td></tr>
              <tr><td class="col-principal">Caja de compensación</td><td class="num col-valor">{{ dinero(r.saldoCompensacion) }}</td></tr>
              <tr><td class="col-principal">Sueldos pendientes</td><td class="num col-valor">- {{ dinero(r.totalSueldosPendientes) }}</td></tr>
              <tr><td class="col-principal">Deuda con proveedores</td><td class="num col-valor">- {{ dinero(r.totalDeudaProveedores) }}</td></tr>
            </tbody>
            <tfoot>
              <tr><td class="col-principal">Balance proyectado</td><td class="num col-valor">{{ dinero(balance(r)) }}</td></tr>
            </tfoot>
          </table>
        </div>
      </section>
    }

    <div class="rep-grid">
      <section class="rep-card">
        <h3>Entró y salió por mes</h3>
        <p class="rep-ayuda">Se ve de un vistazo en qué meses salió más de lo que entró.</p>
        <app-rep-barras [series]="series" [grupos]="grupos()" [formato]="dinero" />
      </section>

      <section class="rep-card">
        <h3>Por caja</h3>
        <p class="rep-ayuda">Cuánto del efectivo y cuánto del banco.</p>
        <div class="rep-tabla">
          <table>
            <thead><tr><th>Caja</th><th class="num">Entró</th><th class="num">Salió</th></tr></thead>
            <tbody>
              @for (c of res().porCaja; track c.caja) {
                <tr>
                  <td class="col-principal">{{ nombreCaja(c.caja) }}</td>
                  <td class="num" data-label="Entró">{{ dinero(c.ingresos) }}</td>
                  <td class="num" data-label="Salió">{{ dinero(c.egresos) }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </section>
    </div>

    <section class="rep-card">
      <h3>Los mayores egresos</h3>
      <p class="rep-ayuda">Las diez salidas de plata más grandes del período.</p>
      @if (res().mayoresEgresos.length === 0) {
        <p class="rep-vacio">No hubo egresos en este período.</p>
      } @else {
        <div class="rep-tabla">
          <table>
            <thead><tr><th>Concepto</th><th>Fecha</th><th>Caja</th><th class="num">Monto</th></tr></thead>
            <tbody>
              @for (m of res().mayoresEgresos; track m.id) {
                <tr>
                  <td class="col-principal">{{ m.concepto }}</td>
                  <td data-label="Fecha">{{ fechaDe(m) }}</td>
                  <td data-label="Caja">{{ nombreCaja(m.tipoCaja) }}</td>
                  <td class="num col-valor">{{ dinero(m.monto) }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <p class="rep-ayuda despues">{{ pluralizar(delPeriodo().length, 'movimiento', 'movimientos') }} en el período entre ingresos y egresos.</p>
      }
    </section>
  `,
})
export class ReportesCajaComponent {
  /** null = no se pudieron traer. */
  readonly movimientos = input.required<CajaMovimientoResponse[] | null>();
  readonly resumen = input.required<ResumenCajasResponse | null>();
  readonly rango = input.required<Rango>();
  readonly etiquetaPeriodo = input.required<string>();
  readonly meses = input.required<Mes[]>();

  protected readonly dinero = dinero;
  protected readonly pluralizar = pluralizar;

  protected readonly series: SerieBarras[] = [
    { nombre: 'Entró', color: 'var(--accent)' },
    { nombre: 'Salió', color: 'var(--color-warning)' },
  ];

  protected readonly res = computed(() => calcularCaja(this.movimientos() ?? [], this.rango(), this.meses()));
  protected readonly grupos = computed<GrupoBarras[]>(() => this.res().serie.map(s => ({ etiqueta: s.mes, valores: [s.ingresos, s.egresos] })));
  protected readonly delPeriodo = computed(() =>
    (this.movimientos() ?? []).filter(m => m.tipoCaja !== 'COMPENSACION' && enRango(parsearFecha(m.fechaMovimiento), this.rango())));

  protected nombreCaja(c: TipoCaja): string {
    return NOMBRE_CAJA[c];
  }

  protected fechaDe(m: CajaMovimientoResponse): string {
    return fecha(parsearFecha(m.fechaMovimiento));
  }

  protected balance(r: ResumenCajasResponse): number {
    return r.saldoFisica + r.saldoBancaria + r.saldoCompensacion - r.totalSueldosPendientes - r.totalDeudaProveedores;
  }

  protected exportar(): void {
    descargarCsv('reporte-caja',
      ['Fecha', 'Caja', 'Tipo', 'Concepto', 'Monto', 'Referencia', 'Cargó'],
      [...this.delPeriodo()]
        .sort((a, b) => a.fechaMovimiento.localeCompare(b.fechaMovimiento))
        .map(m => [this.fechaDe(m), NOMBRE_CAJA[m.tipoCaja], m.tipo === 'INGRESO' ? 'Ingreso' : 'Egreso', m.concepto, m.monto, m.referencia, m.creadoPor]));
  }
}
