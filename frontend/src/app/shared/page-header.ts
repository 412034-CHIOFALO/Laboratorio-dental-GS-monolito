import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Encabezado de cada pantalla del panel: título (con una parte resaltada opcional), una línea que dice para qué sirve
 * la pantalla o la sección que se está viendo, y a la derecha los botones de acción.
 *
 * El subtítulo se pasa como texto (`subtitulo`) o, si lleva cifras con formato propio, como contenido con el atributo
 * `subtitulo`; los botones van con el atributo `acciones`:
 *
 *   <app-page-header titulo="Pedidos" subtitulo="12 en total">
 *     <button acciones class="btn-create">Nuevo pedido</button>
 *   </app-page-header>
 */
@Component({
  selector: 'app-page-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="page-header">
      <div class="page-title">
        <h1>{{ titulo() }}@if (acento()) {<span class="accent"> {{ acento() }}</span>}</h1>
        <p class="page-sub">{{ subtitulo() }}<ng-content select="[subtitulo]" /></p>
      </div>
      <div class="page-actions"><ng-content select="[acciones]" /></div>
    </header>
  `,
  styles: `
    :host { display: block; flex-shrink: 0; }
    .page-header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 1rem;
      flex-wrap: wrap;
    }
    .page-title { min-width: 0; }
    h1 {
      font-size: 1.5rem;
      font-weight: 800;
      color: var(--gs-text);
      letter-spacing: -0.5px;
      margin: 0;
    }
    h1 .accent { color: var(--gs-green); }
    .page-sub {
      color: var(--gs-text-muted);
      font-size: 0.85rem;
      margin: 0.25rem 0 0;
    }
    .page-sub:empty { display: none; }
    .page-actions { display: flex; align-items: center; gap: 0.5rem; flex-wrap: wrap; }
    .page-actions:empty { display: none; }
  `,
})
export class PageHeaderComponent {
  readonly titulo = input.required<string>();
  /** Parte del título que se muestra resaltada ("Stock" + "de materiales"). */
  readonly acento = input('');
  readonly subtitulo = input('');
}
