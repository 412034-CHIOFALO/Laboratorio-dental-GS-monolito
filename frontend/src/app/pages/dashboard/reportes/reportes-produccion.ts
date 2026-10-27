import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { EstadoPedido, PedidoResponse } from '../../../services/pedidos.service';
import { descargarCsv } from '../../../shared/csv.util';
import { Rango, calcularProduccion, enRango, parsearFecha } from './reportes.calculos';
import { cantidadDeDias, dinero, fecha, pluralizar, porcentaje } from './reportes.formato';
import { ItemRanking, RepRankingComponent } from './rep-graficos';

const ESTADO: Record<EstadoPedido, { texto: string; color: string }> = {
  RECIBIDO: { texto: 'Recibido', color: 'var(--color-info)' },
  EN_PROCESO: { texto: 'En proceso', color: 'var(--color-warning)' },
  CONTROL: { texto: 'En control', color: '#a855f7' },
  LISTO: { texto: 'Listo para entregar', color: 'var(--color-success)' },
  ENTREGADO: { texto: 'Entregado', color: '#06b6d4' },
  CANCELADO: { texto: 'Cancelado', color: 'var(--color-danger)' },
};

/** Cuánto se produce, cuánto se tarda y cuánto se cumple con lo prometido. */
@Component({
  selector: 'app-reportes-produccion',
  imports: [RepRankingComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './rep-panel.css',
  template: `
    <div class="rep-barra">
      <p class="rep-barra-texto">{{ etiquetaPeriodo() }}. Se cuentan los pedidos que entraron en el período; los «pasados de fecha» son los de hoy, sin importar cuándo entraron.</p>
      <button type="button" class="rep-btn" [disabled]="delPeriodo().length === 0" (click)="exportar()">Exportar a Excel (CSV)</button>
    </div>

    <div class="rep-kpis">
      <div class="rep-kpi">
        <span class="rep-kpi-valor">{{ res().creados }}</span>
        <span class="rep-kpi-etiqueta">Pedidos recibidos</span>
        <span class="rep-kpi-sub">{{ pluralizar(res().urgentes, 'urgente', 'urgentes') }}@if (res().creados > 0) { ({{ porcentaje(res().urgentes / res().creados) }}) }</span>
      </div>
      <div class="rep-kpi">
        <span class="rep-kpi-valor">{{ res().entregados }}</span>
        <span class="rep-kpi-etiqueta">Entregados</span>
        <span class="rep-kpi-sub">{{ res().enCurso }} siguen en curso · {{ res().cancelados }} cancelados</span>
      </div>
      <div class="rep-kpi" [class]="claseEnTermino()">
        <span class="rep-kpi-valor">{{ porcentaje(res().enTermino) }}</span>
        <span class="rep-kpi-etiqueta">Entregados en término</span>
        <span class="rep-kpi-sub">En la fecha prometida o antes</span>
      </div>
      <div class="rep-kpi">
        <span class="rep-kpi-valor">{{ cantidadDeDias(res().diasPromedio) }}</span>
        <span class="rep-kpi-etiqueta">Tiempo de producción</span>
        <span class="rep-kpi-sub">Desde que entra el pedido hasta que se entrega</span>
      </div>
      <div class="rep-kpi" [class]="res().vencidos.length > 0 ? 'malo' : 'ok'">
        <span class="rep-kpi-valor">{{ res().vencidos.length }}</span>
        <span class="rep-kpi-etiqueta">Pasados de fecha hoy</span>
        <span class="rep-kpi-sub">En curso con la entrega ya vencida</span>
      </div>
    </div>

    @if (res().vencidos.length > 0) {
      <section class="rep-card">
        <h3>Pasados de fecha</h3>
        <p class="rep-ayuda">Pedidos en curso cuya fecha de entrega ya pasó, los más atrasados primero.</p>
        <div class="rep-tabla">
          <table>
            <thead><tr><th>Pedido</th><th>Odontólogo</th><th>Trabajo</th><th>Estado</th><th class="num">Atraso</th></tr></thead>
            <tbody>
              @for (v of vencidos(); track v.pedido.id) {
                <tr>
                  <td class="col-principal">{{ v.pedido.nroPedido }}</td>
                  <td data-label="Odontólogo">{{ v.pedido.odontologoNombre }}</td>
                  <td data-label="Trabajo">{{ v.pedido.trabajo }}</td>
                  <td data-label="Estado">{{ estado(v.pedido.estado).texto }}</td>
                  <td class="num col-valor rep-pago-dias">{{ cantidadDeDias(v.diasVencido) }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        @if (res().vencidos.length > vencidos().length) {
          <p class="rep-ayuda despues">Se muestran 10 de {{ res().vencidos.length }}.</p>
        }
      </section>
    }

    <div class="rep-grid">
      <section class="rep-card">
        <h3>Cómo están los pedidos del período</h3>
        <p class="rep-ayuda">Dónde está hoy cada pedido que entró en el período.</p>
        <app-rep-ranking [items]="porEstado()" vacio="No entró ningún pedido en este período." />
      </section>

      <section class="rep-card">
        <h3>Trabajos más pedidos</h3>
        <p class="rep-ayuda">Los ocho primeros, con lo que se acordó cobrar por ellos.</p>
        <app-rep-ranking [items]="porTrabajo()" vacio="No entró ningún pedido en este período." />
      </section>
    </div>

    <section class="rep-card">
      <h3>Por técnico</h3>
      <p class="rep-ayuda">Cuánto trabajó cada uno y cuánto de lo que entregó salió en término.</p>
      @if (res().porTecnico.length === 0) {
        <p class="rep-vacio">No entró ningún pedido en este período.</p>
      } @else {
        <div class="rep-tabla">
          <table>
            <thead><tr><th>Técnico</th><th class="num">Pedidos</th><th class="num">Entregados</th><th class="num">En término</th></tr></thead>
            <tbody>
              @for (t of res().porTecnico; track t.tecnico) {
                <tr>
                  <td class="col-principal">{{ t.tecnico }}</td>
                  <td class="num col-valor">{{ t.pedidos }}</td>
                  <td class="num" data-label="Entregados">{{ t.entregados }}</td>
                  <td class="num" data-label="En término">{{ porcentaje(t.enTermino) }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </section>
  `,
})
export class ReportesProduccionComponent {
  readonly pedidos = input.required<PedidoResponse[]>();
  readonly rango = input.required<Rango>();
  readonly etiquetaPeriodo = input.required<string>();
  readonly hoy = input.required<Date>();

  protected readonly porcentaje = porcentaje;
  protected readonly cantidadDeDias = cantidadDeDias;
  protected readonly pluralizar = pluralizar;

  protected readonly res = computed(() => calcularProduccion(this.pedidos(), this.rango(), this.hoy()));
  protected readonly delPeriodo = computed(() => this.pedidos().filter(p => enRango(parsearFecha(p.fechaCreacion), this.rango())));
  protected readonly vencidos = computed(() => this.res().vencidos.slice(0, 10));

  protected estado(e: EstadoPedido): { texto: string; color: string } {
    return ESTADO[e];
  }

  protected claseEnTermino(): string {
    const f = this.res().enTermino;
    return f == null ? '' : f >= 0.9 ? 'ok' : f >= 0.7 ? 'aviso' : 'malo';
  }

  protected readonly porEstado = computed<ItemRanking[]>(() =>
    this.res().porEstado.filter(e => e.cantidad > 0).map(e => ({
      etiqueta: ESTADO[e.estado].texto, valor: e.cantidad, texto: String(e.cantidad), color: ESTADO[e.estado].color,
    })));

  protected readonly porTrabajo = computed<ItemRanking[]>(() =>
    this.res().porTrabajo.slice(0, 8).map(t => ({
      etiqueta: t.trabajo, valor: t.cantidad, texto: String(t.cantidad),
      detalle: `${dinero(t.facturado)} acordados${t.urgentes > 0 ? ` · ${pluralizar(t.urgentes, 'urgente', 'urgentes')}` : ''}`,
    })));

  protected exportar(): void {
    descargarCsv('reporte-produccion',
      ['Pedido', 'Fecha de ingreso', 'Odontólogo', 'Paciente', 'Trabajo', 'Técnico', 'Estado', 'Prioridad', 'Entrega prometida', 'Entrega real', 'Precio acordado'],
      this.delPeriodo().map(p => [
        p.nroPedido, fecha(parsearFecha(p.fechaCreacion)), p.odontologoNombre, p.paciente, p.trabajo, p.tecnicoNombre ?? 'Sin asignar',
        ESTADO[p.estado].texto, p.prioridad === 'URGENTE' ? 'Urgente' : 'Normal', fecha(parsearFecha(p.fechaEntrega)),
        fecha(parsearFecha(p.fechaEntregaReal)), p.precioAcordado,
      ]));
  }
}
