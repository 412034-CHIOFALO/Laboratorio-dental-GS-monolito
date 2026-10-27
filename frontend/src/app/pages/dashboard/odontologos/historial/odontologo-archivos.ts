import { ChangeDetectionStrategy, Component, OnDestroy, OnInit, inject, input, output, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Observable, from, of } from 'rxjs';
import { catchError, map, mergeMap, toArray } from 'rxjs/operators';

import { PedidoResponse } from '../../../../services/pedidos.service';
import { EscaneoResponse, EscaneosService } from '../../../../services/escaneos.service';
import { DocumentoResponse, DocumentosService } from '../../../../services/documentos.service';
import { NotificationService } from '../../../../services/notification.service';
import { describirMalla, describirTamanio } from '../../../../shared/malla.util';
import { Visor3dComponent } from '../../escaneos/visor3d.component';

/** Cuántos pedidos (los más recientes) se revisan: cada uno es una consulta, y un odontólogo viejo puede tener cientos. */
const MAX_PEDIDOS = 40;
/** Consultas en simultáneo, para no saturar al servidor. */
const CONCURRENCIA = 6;
const VISUALIZABLES_3D = ['.stl', '.obj', '.ply'];

interface Archivo {
  clave: string;
  id: number;
  pedidoId: number;
  nroPedido: string;
  paciente: string;
  nombre: string;
  fecha: string;
  tamanio: string;
  detalle: string;
  seAbreEnPestania: boolean;
  puedeVer3d: boolean;
}

/**
 * Los archivos de todos los pedidos de un odontólogo (escaneos 3D o documentos) en una sola lista, del más nuevo al más
 * viejo. Los archivos viven en cada pedido; acá se juntan para no tener que abrirlos uno por uno.
 */
@Component({
  selector: 'app-odontologo-archivos',
  imports: [DatePipe, Visor3dComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (cargando()) {
      <div class="arch-estado"><div class="spinner-lg"></div><span>Buscando los archivos de sus pedidos…</span></div>
    } @else {
      @if (fallos() > 0) {
        <p class="arch-aviso" role="alert">
          No se pudieron consultar los archivos de {{ fallos() }} {{ fallos() === 1 ? 'pedido' : 'pedidos' }}: la lista puede estar incompleta.
        </p>
      }

      @if (archivos().length === 0) {
        <div class="arch-vacio">
          <p><strong>{{ tipo() === 'escaneos' ? 'Todavía no hay escaneos 3D de este odontólogo.' : 'Todavía no hay documentos de este odontólogo.' }}</strong></p>
          <p>
            Se agregan solos cuando un mail trae
            {{ tipo() === 'escaneos' ? 'un escaneo' : 'documentos' }}
            adjunto, o a mano desde la sección «{{ tipo() === 'escaneos' ? 'Escaneos' : 'Documentos' }}».
          </p>
        </div>
      } @else {
        <ul class="arch-lista" [attr.aria-label]="tipo() === 'escaneos' ? 'Escaneos 3D' : 'Documentos'">
          @for (a of archivos(); track a.clave) {
            <li [class.escaneo]="tipo() === 'escaneos'">
              <span class="arch-icono" aria-hidden="true">
                @if (tipo() === 'escaneos') {
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><polyline points="3.27 6.96 12 12.01 20.73 6.96"/><line x1="12" y1="22.08" x2="12" y2="12"/></svg>
                } @else {
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
                }
              </span>
              <div class="arch-datos">
                <span class="arch-nombre">{{ a.nombre }}</span>
                <span class="arch-meta">
                  <button type="button" class="arch-pedido" (click)="verPedido.emit(a.pedidoId)">{{ a.nroPedido }}</button>
                  · {{ a.paciente }} · {{ a.fecha | date:'dd/MM/yyyy' }}@if (a.tamanio) { · {{ a.tamanio }} }
                </span>
                @if (a.detalle) { <span class="arch-meta">{{ a.detalle }}</span> }
              </div>
              <div class="arch-acciones">
                @if (a.puedeVer3d) {
                  <button type="button" class="arch-btn" [disabled]="trabajando() === a.clave" (click)="ver3d(a)">
                    {{ trabajando() === a.clave ? 'Abriendo…' : 'Ver en 3D' }}
                  </button>
                }
                <button type="button" class="arch-btn" [disabled]="trabajando() === a.clave" (click)="descargar(a)">
                  {{ a.seAbreEnPestania ? 'Abrir' : 'Descargar' }}
                </button>
              </div>
            </li>
          }
        </ul>
        @if (truncado()) {
          <p class="arch-nota">Se muestran los archivos de sus {{ maxPedidos }} pedidos más recientes. Los anteriores se ven desde «{{ tipo() === 'escaneos' ? 'Escaneos' : 'Documentos' }}».</p>
        }
      }
    }

    @if (visor(); as v) {
      <app-visor3d [url]="v.url" [fileName]="v.nombre" (cerrar)="cerrarVisor()" />
    }
  `,
  styles: `
    :host { display: block; }
    .arch-estado, .arch-vacio { padding: 2.5rem 1rem; text-align: center; color: var(--muted, #6b7280); background: var(--card); border: 1px dashed var(--border-med, var(--border)); border-radius: 12px; }
    .arch-estado { display: flex; flex-direction: column; align-items: center; gap: 0.75rem; }
    .arch-vacio p { margin: 0; font-size: 0.85rem; line-height: 1.5; }
    .arch-vacio p + p { margin-top: 0.3rem; }
    .arch-vacio strong { color: var(--text); }
    .arch-aviso {
      margin: 0 0 0.75rem; padding: 0.6rem 0.85rem; font-size: 0.82rem; color: var(--text); border-radius: 10px;
      background: color-mix(in srgb, var(--color-warning) 12%, var(--card)); border: 1px solid color-mix(in srgb, var(--color-warning) 45%, transparent);
    }
    .arch-nota { margin: 0.75rem 0 0; font-size: 0.75rem; color: var(--muted, #6b7280); }
    .arch-lista { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 0.5rem; }
    .arch-lista li {
      display: flex; flex-wrap: wrap; align-items: center; gap: 0.75rem; padding: 0.7rem 0.9rem;
      background: var(--card); border: 1px solid var(--border); border-radius: 12px;
    }
    .arch-icono {
      flex-shrink: 0; width: 40px; height: 40px; display: grid; place-items: center; border-radius: 10px;
      background: color-mix(in srgb, var(--color-info) 14%, transparent); color: var(--color-info);
    }
    .arch-lista li.escaneo .arch-icono { background: color-mix(in srgb, var(--accent, #22c55e) 16%, transparent); color: var(--accent, #22c55e); }
    .arch-datos { display: flex; flex-direction: column; gap: 0.15rem; min-width: 0; flex: 1 1 220px; }
    .arch-nombre { font-size: 0.88rem; font-weight: 600; color: var(--text); overflow-wrap: anywhere; }
    .arch-meta { font-size: 0.76rem; color: var(--muted, #6b7280); line-height: 1.4; }
    .arch-pedido {
      padding: 0; background: none; border: none; font: inherit; font-weight: 600; cursor: pointer;
      color: var(--accent, #22c55e); text-decoration: underline; text-underline-offset: 2px;
    }
    .arch-pedido:focus-visible, .arch-btn:focus-visible { outline: 2px solid var(--accent, #22c55e); outline-offset: 2px; }
    .arch-acciones { display: flex; flex-wrap: wrap; gap: 0.4rem; margin-left: auto; }
    .arch-btn {
      padding: 0.4rem 0.8rem; font: inherit; font-size: 0.78rem; font-weight: 600; cursor: pointer; white-space: nowrap;
      color: var(--text); background: transparent; border: 1px solid var(--border-med, var(--border)); border-radius: 8px;
    }
    .arch-btn:hover:not(:disabled) { border-color: var(--border-strong, var(--text)); }
    .arch-btn:disabled { opacity: 0.5; cursor: not-allowed; }
    @media (max-width: 560px) { .arch-acciones { margin-left: 0; width: 100%; } }
  `,
})
export class OdontologoArchivosComponent implements OnInit, OnDestroy {
  private readonly escaneosSvc = inject(EscaneosService);
  private readonly documentosSvc = inject(DocumentosService);
  private readonly notif = inject(NotificationService);

  readonly tipo = input.required<'escaneos' | 'documentos'>();
  /** Los pedidos del odontólogo, del más nuevo al más viejo. */
  readonly pedidos = input.required<PedidoResponse[]>();
  readonly verPedido = output<number>();

  readonly maxPedidos = MAX_PEDIDOS;
  readonly cargando = signal(true);
  readonly archivos = signal<Archivo[]>([]);
  readonly fallos = signal(0);
  readonly truncado = signal(false);
  /** Archivo que se está bajando para mirarlo o guardarlo. */
  readonly trabajando = signal<string | null>(null);
  readonly visor = signal<{ url: string; nombre: string } | null>(null);
  private urlVisor: string | null = null;

  // Se busca una vez, al abrir la pestaña: el listado de pedidos se refresca solo cada tanto y no tiene por qué volver a
  // consultar los archivos de todos cada vez.
  ngOnInit(): void {
    const todos = this.pedidos();
    const pedidos = todos.slice(0, MAX_PEDIDOS);
    this.truncado.set(todos.length > MAX_PEDIDOS);
    if (pedidos.length === 0) {
      this.cargando.set(false);
      return;
    }
    let fallos = 0;
    from(pedidos).pipe(
      mergeMap(p => this.archivosDe(p).pipe(catchError(() => { fallos++; return of([] as Archivo[]); })), CONCURRENCIA),
      toArray(),
    ).subscribe(grupos => {
      this.archivos.set(grupos.flat().sort((a, b) => b.fecha.localeCompare(a.fecha)));
      this.fallos.set(fallos);
      this.cargando.set(false);
    });
  }

  private archivosDe(p: PedidoResponse): Observable<Archivo[]> {
    return this.tipo() === 'escaneos'
      ? this.escaneosSvc.listar(p.id).pipe(map(es => es.map(e => this.deEscaneo(e, p))))
      : this.documentosSvc.listar(p.id).pipe(map(ds => ds.map(d => this.deDocumento(d, p))));
  }

  private deEscaneo(e: EscaneoResponse, p: PedidoResponse): Archivo {
    const nombre = e.fileName.toLowerCase();
    return {
      clave: `e${e.id}`, id: e.id, pedidoId: p.id, nroPedido: p.nroPedido, paciente: p.paciente,
      nombre: e.fileName, fecha: e.fechaSubida, tamanio: describirTamanio(e.tamanioBytes), detalle: describirMalla(e),
      seAbreEnPestania: false, puedeVer3d: VISUALIZABLES_3D.some(ext => nombre.endsWith(ext)),
    };
  }

  private deDocumento(d: DocumentoResponse, p: PedidoResponse): Archivo {
    const tipo = d.contentType ?? '';
    return {
      clave: `d${d.id}`, id: d.id, pedidoId: p.id, nroPedido: p.nroPedido, paciente: p.paciente,
      nombre: d.fileName, fecha: d.fechaSubida, tamanio: describirTamanio(d.tamanioBytes), detalle: '',
      seAbreEnPestania: tipo === 'application/pdf' || tipo.startsWith('image/'), puedeVer3d: false,
    };
  }

  private bajar(a: Archivo): Observable<Blob> {
    return this.tipo() === 'escaneos'
      ? this.escaneosSvc.descargar(a.pedidoId, a.id)
      : this.documentosSvc.descargar(a.pedidoId, a.id);
  }

  ver3d(a: Archivo): void {
    if (this.trabajando() != null) return;
    this.trabajando.set(a.clave);
    this.bajar(a).subscribe({
      next: blob => {
        this.trabajando.set(null);
        this.liberarVisor();
        this.urlVisor = URL.createObjectURL(blob);
        this.visor.set({ url: this.urlVisor, nombre: a.nombre });
      },
      error: err => { this.trabajando.set(null); this.notif.errorHttp(err, `No se pudo abrir ${a.nombre}`); },
    });
  }

  /** PDFs e imágenes se abren en otra pestaña; el resto (escaneos, planillas…) se baja al disco. */
  descargar(a: Archivo): void {
    if (this.trabajando() != null) return;
    this.trabajando.set(a.clave);
    this.bajar(a).subscribe({
      next: blob => {
        this.trabajando.set(null);
        const url = URL.createObjectURL(blob);
        if (a.seAbreEnPestania) {
          window.open(url, '_blank');
        } else {
          const enlace = document.createElement('a');
          enlace.href = url;
          enlace.download = a.nombre;
          enlace.click();
        }
        setTimeout(() => URL.revokeObjectURL(url), 15_000);
      },
      error: err => { this.trabajando.set(null); this.notif.errorHttp(err, `No se pudo descargar ${a.nombre}`); },
    });
  }

  cerrarVisor(): void {
    this.visor.set(null);
    this.liberarVisor();
  }

  private liberarVisor(): void {
    if (this.urlVisor) URL.revokeObjectURL(this.urlVisor);
    this.urlVisor = null;
  }

  ngOnDestroy(): void {
    this.liberarVisor();
  }
}
