import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

export interface SerieBarras { nombre: string; color: string; }
export interface GrupoBarras { etiqueta: string; valores: number[]; }
export interface ItemRanking { etiqueta: string; valor: number; texto: string; detalle?: string; color?: string; }

/** Un monto corto para el eje: $1,2 M, $350 K. */
export function montoCorto(n: number): string {
  const abs = Math.abs(n);
  const signo = n < 0 ? '-' : '';
  if (abs >= 1_000_000) return `${signo}$${(abs / 1_000_000).toLocaleString('es-AR', { maximumFractionDigits: 1 })} M`;
  if (abs >= 1_000) return `${signo}$${Math.round(abs / 1_000)} K`;
  return `${signo}$${Math.round(abs)}`;
}

/** El tope del eje: el máximo redondeado hacia arriba a 1, 2, 2,5, 5 o 10 por la potencia de diez que corresponda. */
export function topeDelEje(max: number): number {
  if (max <= 0) return 1;
  const potencia = 10 ** Math.floor(Math.log10(max));
  const paso = [1, 2, 2.5, 5, 10].find(p => p * potencia >= max) ?? 10;
  return paso * potencia;
}

const ANCHO = 640;
const ALTO = 220;
const MARGEN = { izq: 46, der: 8, arriba: 12, abajo: 26 };

/**
 * Barras agrupadas por período (por ejemplo, facturado y cobrado de cada mes). Sin librerías: es un SVG que se escala al
 * ancho de la tarjeta, con colores del tema y un título en cada barra para el que quiera ver el valor exacto.
 */
@Component({
  selector: 'app-rep-barras',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="leyenda">
      @for (s of series(); track s.nombre) {
        <span class="leyenda-item"><span class="leyenda-punto" [style.background]="s.color"></span>{{ s.nombre }}</span>
      }
    </div>
    <svg [attr.viewBox]="'0 0 ' + ancho + ' ' + alto" role="img" [attr.aria-label]="descripcion()" preserveAspectRatio="xMidYMid meet">
      @for (g of grilla(); track g.valor) {
        <line [attr.x1]="margen.izq" [attr.x2]="ancho - margen.der" [attr.y1]="g.y" [attr.y2]="g.y" class="grilla" />
        <text [attr.x]="margen.izq - 6" [attr.y]="g.y + 3" text-anchor="end" class="eje">{{ g.texto }}</text>
      }
      @for (b of barras(); track b.clave) {
        <rect [attr.x]="b.x" [attr.y]="b.y" [attr.width]="b.ancho" [attr.height]="b.alto" rx="3" [style.fill]="b.color">
          <title>{{ b.titulo }}</title>
        </rect>
      }
      @for (e of etiquetas(); track e.x) {
        <text [attr.x]="e.x" [attr.y]="alto - 8" text-anchor="middle" class="eje">{{ e.texto }}</text>
      }
    </svg>
  `,
  styles: `
    :host { display: block; }
    svg { width: 100%; height: auto; display: block; }
    .leyenda { display: flex; flex-wrap: wrap; gap: 0.9rem; margin-bottom: 0.5rem; }
    .leyenda-item { display: inline-flex; align-items: center; gap: 0.35rem; font-size: 0.75rem; color: var(--gs-text-muted); }
    .leyenda-punto { width: 9px; height: 9px; border-radius: 3px; }
    .grilla { stroke: var(--gs-border); stroke-width: 1; }
    .eje { font-size: 10px; fill: var(--gs-text-dim); font-family: inherit; font-variant-numeric: tabular-nums; }
    rect { transition: opacity 0.15s; }
    rect:hover { opacity: 0.8; }
    @media (prefers-reduced-motion: reduce) { rect { transition: none; } }
  `,
})
export class RepBarrasComponent {
  readonly series = input.required<SerieBarras[]>();
  readonly grupos = input.required<GrupoBarras[]>();
  /** Cómo se escribe un monto completo en el título de cada barra. */
  readonly formato = input<(n: number) => string>(n => n.toLocaleString('es-AR'));

  protected readonly ancho = ANCHO;
  protected readonly alto = ALTO;
  protected readonly margen = MARGEN;

  private readonly tope = computed(() => topeDelEje(Math.max(0, ...this.grupos().flatMap(g => g.valores))));
  private readonly altoUtil = ALTO - MARGEN.arriba - MARGEN.abajo;
  private readonly anchoUtil = ANCHO - MARGEN.izq - MARGEN.der;

  protected readonly grilla = computed(() =>
    [0, 0.25, 0.5, 0.75, 1].map(f => ({
      valor: f, y: MARGEN.arriba + this.altoUtil * (1 - f), texto: f === 0 ? '$0' : montoCorto(this.tope() * f),
    })));

  protected readonly barras = computed(() => {
    const grupos = this.grupos();
    const series = this.series();
    const ancho = this.anchoUtil / Math.max(grupos.length, 1);
    const anchoBarra = Math.min(26, (ancho * 0.7) / Math.max(series.length, 1));
    const total = anchoBarra * series.length;
    return grupos.flatMap((g, i) => g.valores.map((v, j) => {
      const alto = (Math.max(v, 0) / this.tope()) * this.altoUtil;
      return {
        clave: `${i}-${j}`,
        x: MARGEN.izq + ancho * i + (ancho - total) / 2 + anchoBarra * j,
        y: MARGEN.arriba + this.altoUtil - alto,
        ancho: anchoBarra - 1,
        alto: Math.max(alto, v > 0 ? 1 : 0),
        color: series[j].color,
        titulo: `${g.etiqueta} · ${series[j].nombre}: ${this.formato()(v)}`,
      };
    }));
  });

  protected readonly etiquetas = computed(() => {
    const ancho = this.anchoUtil / Math.max(this.grupos().length, 1);
    return this.grupos().map((g, i) => ({ x: MARGEN.izq + ancho * i + ancho / 2, texto: g.etiqueta }));
  });

  protected readonly descripcion = computed(() =>
    `Gráfico de barras: ${this.series().map(s => s.nombre).join(' y ')} por período. ` +
    this.grupos().map(g => `${g.etiqueta}: ${g.valores.map(v => this.formato()(v)).join(' / ')}`).join('; '));
}

/** Una lista de barras horizontales ordenada por quien la use: etiqueta, barra proporcional y el valor escrito. */
@Component({
  selector: 'app-rep-ranking',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (items().length === 0) {
      <p class="vacio">{{ vacio() }}</p>
    } @else {
      <ul class="ranking">
        @for (it of items(); track it.etiqueta) {
          <li>
            <div class="fila-cab">
              <span class="etiqueta" [title]="it.etiqueta">{{ it.etiqueta }}</span>
              <span class="texto">{{ it.texto }}</span>
            </div>
            <div class="pista"><div class="relleno" [style.width.%]="porcentaje(it.valor)" [style.background]="it.color ?? 'var(--accent)'"></div></div>
            @if (it.detalle) { <span class="detalle">{{ it.detalle }}</span> }
          </li>
        }
      </ul>
    }
  `,
  styles: `
    :host { display: block; }
    .ranking { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 0.75rem; }
    .fila-cab { display: flex; justify-content: space-between; align-items: baseline; gap: 0.75rem; }
    .etiqueta { font-size: 0.82rem; color: var(--gs-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
    .texto { font-size: 0.82rem; font-weight: 600; color: var(--gs-text); white-space: nowrap; font-variant-numeric: tabular-nums; }
    .pista { height: 7px; margin-top: 0.3rem; background: var(--gs-border); border-radius: 99px; overflow: hidden; }
    .relleno { height: 100%; border-radius: 99px; transition: width 0.5s ease; }
    .detalle { display: block; margin-top: 0.2rem; font-size: 0.72rem; color: var(--gs-text-dim); }
    .vacio { margin: 0; padding: 1rem 0; font-size: 0.82rem; color: var(--gs-text-muted); text-align: center; }
    @media (prefers-reduced-motion: reduce) { .relleno { transition: none; } }
  `,
})
export class RepRankingComponent {
  readonly items = input.required<ItemRanking[]>();
  readonly vacio = input('Sin datos en este período.');

  private readonly maximo = computed(() => Math.max(1, ...this.items().map(i => i.valor)));

  protected porcentaje(valor: number): number {
    return Math.max(valor > 0 ? 2 : 0, Math.round((valor / this.maximo()) * 100));
  }
}
