import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { PedidoResponse } from '../../../services/pedidos.service';
import { ComprobanteResponse } from '../../../services/finanzas.service';
import { descargarCsv } from '../../../shared/csv.util';
import { FilaCliente, Rango, calcularClientes, EstadoCliente } from './reportes.calculos';
import { cantidadDeDias, dinero, fecha, porcentaje } from './reportes.formato';
import { ItemRanking, RepRankingComponent } from './rep-graficos';

const ETIQUETA_ESTADO: Record<EstadoCliente, { texto: string; clase: string }> = {
  ACTIVO: { texto: 'Activo', clase: 'ok' },
  EN_RIESGO: { texto: 'Hace tiempo que no pide', clase: 'aviso' },
  INACTIVO: { texto: 'Inactivo', clase: 'malo' },
  SIN_PEDIDOS: { texto: 'Sin pedidos', clase: '' },
};

/** Quiénes compran, cuánto, y quiénes dejaron de comprar: el reporte para cuidar la cartera de odontólogos. */
@Component({
  selector: 'app-reportes-clientes',
  imports: [RepRankingComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrl: './rep-panel.css',
  template: `
    @if (!comprobantes()) {
      <p class="rep-aviso" role="alert">No se pudieron cargar los comprobantes, así que lo facturado y la deuda no se pueden calcular ahora. Probá de nuevo en un momento.</p>
    }

    <div class="rep-barra">
      <p class="rep-barra-texto">{{ etiquetaPeriodo() }}. Lo facturado y lo cobrado son de los comprobantes emitidos en el período; la deuda es la de hoy.</p>
      <button type="button" class="rep-btn" [disabled]="res().filas.length === 0" (click)="exportar()">Exportar a Excel (CSV)</button>
    </div>

    <div class="rep-kpis">
      <div class="rep-kpi">
        <span class="rep-kpi-valor">{{ res().activos }}</span>
        <span class="rep-kpi-etiqueta">Odontólogos con pedidos</span>
        <span class="rep-kpi-sub">Pidieron al menos una vez en el período</span>
      </div>
      <div class="rep-kpi">
        <span class="rep-kpi-valor">{{ res().nuevos }}</span>
        <span class="rep-kpi-etiqueta">Clientes nuevos</span>
        <span class="rep-kpi-sub">Hicieron su primer pedido en el período</span>
      </div>
      <div class="rep-kpi">
        <span class="rep-kpi-valor">{{ dinero(res().ticketPromedio) }}</span>
        <span class="rep-kpi-etiqueta">Ticket promedio</span>
        <span class="rep-kpi-sub">Lo facturado por comprobante</span>
      </div>
      <div class="rep-kpi" [class.aviso]="(res().concentracionTop3 ?? 0) > 0.6">
        <span class="rep-kpi-valor">{{ porcentaje(res().concentracionTop3) }}</span>
        <span class="rep-kpi-etiqueta">Lo que traen los 3 mayores</span>
        <span class="rep-kpi-sub">Si es mucho, el laboratorio depende de pocos clientes</span>
      </div>
    </div>

    <div class="rep-grid">
      <section class="rep-card">
        <h3>Quiénes más facturaron</h3>
        <p class="rep-ayuda">Los ocho primeros del período.</p>
        <app-rep-ranking [items]="topFacturacion()" vacio="Todavía no se facturó nada en este período." />
      </section>

      <section class="rep-card">
        <h3>Dejaron de pedir</h3>
        <p class="rep-ayuda">Hace más de 60 días que no mandan un pedido; primero los que más facturaron.</p>
        @if (res().dejaronDePedir.length === 0) {
          <p class="rep-vacio">Nadie dejó de pedir. Buen momento para sumar clientes nuevos.</p>
        } @else {
          <div class="rep-tabla">
            <table>
              <thead><tr><th>Odontólogo</th><th>Último pedido</th><th class="num">Debe</th></tr></thead>
              <tbody>
                @for (f of res().dejaronDePedir; track f.odontologoId) {
                  <tr>
                    <td class="col-principal">{{ f.nombre }}</td>
                    <td data-label="Último pedido">{{ fecha(f.ultimoPedido) }} <span class="muted">· hace {{ cantidadDeDias(f.diasSinPedir) }}</span></td>
                    <td class="num col-valor">{{ dinero(f.saldo) }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>
    </div>

    <section class="rep-card">
      <h3>Todos los odontólogos del período</h3>
      <p class="rep-ayuda">Del que más facturó al que menos.</p>
      @if (res().filas.length === 0) {
        <p class="rep-vacio">No hay pedidos ni comprobantes en este período.</p>
      } @else {
        <div class="rep-tabla">
          <table>
            <thead>
              <tr>
                <th>Odontólogo</th><th class="num">Pedidos</th><th class="num">Facturado</th><th class="num">Cobrado</th>
                <th class="num">Debe hoy</th><th class="num">Ticket</th><th>Último pedido</th><th>Estado</th>
              </tr>
            </thead>
            <tbody>
              @for (f of res().filas; track f.odontologoId) {
                <tr>
                  <td class="col-principal">{{ f.nombre }}</td>
                  <td class="num" data-label="Pedidos">{{ f.pedidos }}</td>
                  <td class="num col-valor">{{ dinero(f.facturado) }}</td>
                  <td class="num" data-label="Cobrado">{{ dinero(f.cobrado) }}</td>
                  <td class="num" data-label="Debe hoy">{{ dinero(f.saldo) }}</td>
                  <td class="num" data-label="Ticket">{{ dinero(f.ticket) }}</td>
                  <td data-label="Último pedido">{{ fecha(f.ultimoPedido) }}</td>
                  <td><span [class]="'rep-chip ' + etiqueta(f).clase">{{ etiqueta(f).texto }}</span></td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </section>
  `,
})
export class ReportesClientesComponent {
  readonly pedidos = input.required<PedidoResponse[]>();
  /** null = no se pudieron traer. */
  readonly comprobantes = input.required<ComprobanteResponse[] | null>();
  readonly rango = input.required<Rango>();
  readonly etiquetaPeriodo = input.required<string>();
  readonly hoy = input.required<Date>();

  protected readonly dinero = dinero;
  protected readonly fecha = fecha;
  protected readonly porcentaje = porcentaje;
  protected readonly cantidadDeDias = cantidadDeDias;

  protected readonly res = computed(() => calcularClientes(this.pedidos(), this.comprobantes() ?? [], this.rango(), this.hoy()));

  protected readonly topFacturacion = computed<ItemRanking[]>(() =>
    this.res().filas.filter(f => f.facturado > 0).slice(0, 8).map(f => ({
      etiqueta: f.nombre, valor: f.facturado, texto: dinero(f.facturado),
      detalle: `${f.pedidos} ${f.pedidos === 1 ? 'pedido' : 'pedidos'}${f.saldo > 0 ? ` · debe ${dinero(f.saldo)}` : ''}`,
    })));

  protected etiqueta(f: FilaCliente): { texto: string; clase: string } {
    return ETIQUETA_ESTADO[f.estado];
  }

  protected exportar(): void {
    descargarCsv('reporte-clientes',
      ['Odontólogo', 'Pedidos', 'Facturado', 'Cobrado', 'Debe hoy', 'Ticket promedio', 'Último pedido', 'Estado'],
      this.res().filas.map(f => [f.nombre, f.pedidos, f.facturado, f.cobrado, f.saldo, f.ticket, fecha(f.ultimoPedido), ETIQUETA_ESTADO[f.estado].texto]));
  }
}
