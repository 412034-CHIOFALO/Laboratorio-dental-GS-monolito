import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { ComprobanteResponse, CuentaCorrienteOdontologoResponse, SeveridadDeuda } from '../../../services/finanzas.service';
import { descargarCsv } from '../../../shared/csv.util';
import { Mes, Rango, calcularCobranzas, enRango, parsearFecha } from './reportes.calculos';
import { cantidadDeDias, dinero, fecha, pluralizar, porcentaje } from './reportes.formato';
import { GrupoBarras, ItemRanking, RepBarrasComponent, RepRankingComponent, SerieBarras } from './rep-graficos';

const SEVERIDAD: Record<SeveridadDeuda, { texto: string; clase: string }> = {
  AL_DIA: { texto: 'Al día', clase: 'ok' },
  BAJA: { texto: 'Reciente', clase: '' },
  MEDIA: { texto: 'Atención', clase: 'aviso' },
  ALTA: { texto: 'Atrasada', clase: 'malo' },
  CRITICA: { texto: 'Crítica', clase: 'malo' },
};

const COLOR_TRAMO = ['var(--color-success)', 'var(--color-warning)', '#f97316', 'var(--color-danger)'];

/** Lo facturado, lo cobrado y lo que se debe: cuánto tarda la plata en volver y quién la tiene. */
@Component({
  selector: 'app-reportes-cobranzas',
  imports: [RepBarrasComponent, RepRankingComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './rep-panel.css',
  template: `
    @if (!comprobantes()) {
      <p class="rep-aviso" role="alert">No se pudieron cargar los comprobantes, así que este reporte no se puede calcular ahora. Probá de nuevo en un momento.</p>
    }

    <div class="rep-barra">
      <p class="rep-barra-texto">{{ etiquetaPeriodo() }}. Lo facturado y lo cobrado son de los comprobantes emitidos en el período; la deuda es la de hoy.</p>
      <button type="button" class="rep-btn" [disabled]="delPeriodo().length === 0" (click)="exportar()">Exportar a Excel (CSV)</button>
    </div>

    <div class="rep-kpis">
      <div class="rep-kpi">
        <span class="rep-kpi-valor">{{ dinero(res().facturado) }}</span>
        <span class="rep-kpi-etiqueta">Facturado</span>
        <span class="rep-kpi-sub">{{ pluralizar(delPeriodo().length, 'comprobante emitido', 'comprobantes emitidos') }}</span>
      </div>
      <div class="rep-kpi" [class]="claseCobrado()">
        <span class="rep-kpi-valor">{{ dinero(res().cobrado) }}</span>
        <span class="rep-kpi-etiqueta">Cobrado</span>
        <span class="rep-kpi-sub">{{ porcentaje(res().porcentajeCobrado) }} de lo facturado</span>
      </div>
      <div class="rep-kpi">
        <span class="rep-kpi-valor">{{ dinero(res().pendiente) }}</span>
        <span class="rep-kpi-etiqueta">Falta cobrar de lo facturado</span>
        <span class="rep-kpi-sub">De los comprobantes de este período</span>
      </div>
      <div class="rep-kpi">
        <span class="rep-kpi-valor">{{ cantidadDeDias(res().plazoPromedio) }}</span>
        <span class="rep-kpi-etiqueta">Plazo de cobro</span>
        <span class="rep-kpi-sub">Desde que se emite hasta que se cobra entero</span>
      </div>
      <div class="rep-kpi" [class]="res().deudaTotal > 0 ? 'aviso' : 'ok'">
        <span class="rep-kpi-valor">{{ dinero(res().deudaTotal) }}</span>
        <span class="rep-kpi-etiqueta">Deuda total hoy</span>
        <span class="rep-kpi-sub">De todos los odontólogos, sin importar el período</span>
      </div>
    </div>

    <div class="rep-grid">
      <section class="rep-card">
        <h3>Facturado y cobrado por mes</h3>
        <p class="rep-ayuda">Cada mes muestra lo que se emitió en él y cuánto de eso ya se cobró.</p>
        <app-rep-barras [series]="series" [grupos]="grupos()" [formato]="dinero" />
      </section>

      <section class="rep-card">
        <h3>Antigüedad de la deuda</h3>
        <p class="rep-ayuda">Cuánto hace que se emitieron los comprobantes que todavía se deben. Cuanto más viejos, más difícil de cobrar.</p>
        <app-rep-ranking [items]="antiguedad()" vacio="No hay deuda pendiente." />
      </section>
    </div>

    <section class="rep-card">
      <h3>Quién debe</h3>
      <p class="rep-ayuda">Odontólogos con deuda hoy, de la mayor a la menor.</p>
      @if (!morosos() || morosos()!.length === 0) {
        <p class="rep-vacio">{{ morosos() ? 'Nadie debe nada.' : 'No se pudo cargar la lista de deudores.' }}</p>
      } @else {
        <div class="rep-tabla">
          <table>
            <thead><tr><th>Odontólogo</th><th class="num">Debe</th><th class="num">Comprobantes</th><th class="num">Sin pagar hace</th><th>Situación</th></tr></thead>
            <tbody>
              @for (m of morosos(); track m.odontologoId) {
                <tr>
                  <td class="col-principal">{{ m.odontologoNombre }}</td>
                  <td class="num col-valor">{{ dinero(m.totalDeuda) }}</td>
                  <td class="num" data-label="Comprobantes">{{ m.comprobantesPendientes }}</td>
                  <td class="num" data-label="Sin pagar hace" [class.rep-pago-dias]="m.diasSinPagar > 60">{{ cantidadDeDias(m.diasSinPagar) }}</td>
                  <td><span [class]="'rep-chip ' + severidad(m).clase">{{ severidad(m).texto }}</span></td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </section>
  `,
})
export class ReportesCobranzasComponent {
  /** null = no se pudieron traer. */
  readonly comprobantes = input.required<ComprobanteResponse[] | null>();
  readonly morosos = input.required<CuentaCorrienteOdontologoResponse[] | null>();
  readonly rango = input.required<Rango>();
  readonly etiquetaPeriodo = input.required<string>();
  readonly hoy = input.required<Date>();
  readonly meses = input.required<Mes[]>();

  protected readonly dinero = dinero;
  protected readonly porcentaje = porcentaje;
  protected readonly cantidadDeDias = cantidadDeDias;
  protected readonly pluralizar = pluralizar;

  protected readonly series: SerieBarras[] = [
    { nombre: 'Facturado', color: 'var(--color-info)' },
    { nombre: 'Cobrado', color: 'var(--accent)' },
  ];

  protected readonly res = computed(() => calcularCobranzas(this.comprobantes() ?? [], this.rango(), this.hoy(), this.meses()));
  protected readonly delPeriodo = computed(() => (this.comprobantes() ?? []).filter(c => enRango(parsearFecha(c.fechaEmision), this.rango())));

  protected readonly grupos = computed<GrupoBarras[]>(() => this.res().serie.map(s => ({ etiqueta: s.mes, valores: [s.facturado, s.cobrado] })));

  protected readonly antiguedad = computed<ItemRanking[]>(() =>
    this.res().deudaTotal === 0 ? [] : this.res().antiguedad.map((t, i) => ({
      etiqueta: t.tramo, valor: t.monto, texto: t.cantidad === 0 ? 'Sin deuda' : `${dinero(t.monto)} · ${pluralizar(t.cantidad, 'comprobante', 'comprobantes')}`,
      color: COLOR_TRAMO[i],
    })));

  protected claseCobrado(): string {
    const f = this.res().porcentajeCobrado;
    return f == null ? '' : f >= 0.8 ? 'ok' : f >= 0.5 ? 'aviso' : 'malo';
  }

  protected severidad(m: CuentaCorrienteOdontologoResponse): { texto: string; clase: string } {
    return SEVERIDAD[m.severidad];
  }

  protected exportar(): void {
    descargarCsv('reporte-cobranzas',
      ['Comprobante', 'Pedido', 'Odontólogo', 'Trabajo', 'Emisión', 'Monto', 'Pagado', 'Saldo', 'Estado', 'Cobrado el'],
      this.delPeriodo().map(c => [
        c.nroComprobante, c.nroPedido, c.odontologoNombre, c.trabajo, fecha(parsearFecha(c.fechaEmision)),
        c.monto, c.montoPagado, c.saldoPendiente, c.estadoPago, fecha(parsearFecha(c.fechaCobro)),
      ]));
  }
}
