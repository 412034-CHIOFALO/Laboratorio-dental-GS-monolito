import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

export type IconoSeccion = 'resumen' | 'cajas' | 'cuentas' | 'pagos' | 'proveedores' | 'sueldos';

export interface SeccionTab {
  id: string;
  etiqueta: string;
  icono?: IconoSeccion;
  /** Numerito al lado de la etiqueta (por ejemplo, cuántos mails esperan); no se muestra si es 0. */
  cantidad?: number;
  /** El numerito se pinta de color de aviso. */
  aviso?: boolean;
}

/**
 * La barra de secciones de una pantalla con varias partes (Finanzas, Solicitudes…): mismas píldoras en todas, con
 * scroll horizontal en el celular. Solo avisa cuál se tocó; la pantalla decide qué mostrar.
 */
@Component({
  selector: 'app-section-tabs',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav class="tabs chips-scroll" [attr.aria-label]="etiquetaAria()">
      @for (s of secciones(); track s.id) {
        <button type="button" class="tab" [class.active]="activa() === s.id"
                [attr.aria-current]="activa() === s.id ? 'page' : null" (click)="seleccionar.emit(s.id)">
          @if (s.icono) {
            <span class="tab-icono" aria-hidden="true">
              @switch (s.icono) {
                @case ('resumen') {
                  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/></svg>
                }
                @case ('cajas') {
                  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12V7H5a2 2 0 0 1 0-4h14v4"/><path d="M3 5v14a2 2 0 0 0 2 2h16v-5"/><path d="M18 12a2 2 0 0 0 0 4h4v-4Z"/></svg>
                }
                @case ('cuentas') {
                  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>
                }
                @case ('pagos') {
                  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M3 21v-5h5"/></svg>
                }
                @case ('proveedores') {
                  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 18V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v11a1 1 0 0 0 1 1h2"/><path d="M15 18H9"/><path d="M19 18h2a1 1 0 0 0 1-1v-3.65a1 1 0 0 0-.22-.62l-3.48-4.35A1 1 0 0 0 17.52 8H14"/><circle cx="17" cy="18" r="2"/><circle cx="7" cy="18" r="2"/></svg>
                }
                @case ('sueldos') {
                  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect width="20" height="12" x="2" y="6" rx="2"/><circle cx="12" cy="12" r="2"/><path d="M6 12h.01M18 12h.01"/></svg>
                }
              }
            </span>
          }
          {{ s.etiqueta }}
          @if (s.cantidad) { <span class="tab-n" [class.aviso]="s.aviso">{{ s.cantidad }}</span> }
        </button>
      }
    </nav>
  `,
  styles: `
    :host { display: block; flex-shrink: 0; }
    .tabs {
      display: flex;
      gap: 0.4rem;
      flex-wrap: wrap;
      padding-bottom: 0.85rem;
      border-bottom: 1px solid var(--border);
    }
    .tab {
      display: inline-flex;
      align-items: center;
      gap: 0.4rem;
      padding: 0.5rem 0.95rem;
      background: transparent;
      border: 1px solid var(--border);
      border-radius: 999px;
      font: inherit;
      font-size: 0.84rem;
      font-weight: 500;
      color: var(--muted, #6b7280);
      cursor: pointer;
      white-space: nowrap;
      transition: all 0.15s;
    }
    .tab:hover { color: var(--text); border-color: color-mix(in srgb, var(--accent, #22c55e) 40%, var(--border)); }
    .tab:focus-visible { outline: 2px solid var(--accent, #22c55e); outline-offset: 2px; }
    .tab.active { background: var(--accent, #22c55e); color: #fff; border-color: var(--accent, #22c55e); font-weight: 600; }
    .tab-icono { display: inline-flex; align-items: center; }
    .tab-icono svg { display: block; }
    .tab-n {
      min-width: 1.15rem;
      padding: 0 0.35rem;
      font-size: 0.68rem;
      line-height: 1.15rem;
      text-align: center;
      border-radius: 999px;
      background: color-mix(in srgb, var(--accent, #22c55e) 22%, transparent);
      color: var(--accent, #22c55e);
      font-variant-numeric: tabular-nums;
    }
    .tab-n.aviso { background: color-mix(in srgb, var(--color-warning) 25%, transparent); color: var(--color-warning); }
    .tab.active .tab-n { background: rgba(255, 255, 255, 0.28); color: #fff; }
    @media (prefers-reduced-motion: reduce) { .tab { transition: none; } }
  `,
})
export class SectionTabsComponent {
  readonly secciones = input.required<SeccionTab[]>();
  readonly activa = input.required<string>();
  readonly etiquetaAria = input('Secciones');
  readonly seleccionar = output<string>();
}
