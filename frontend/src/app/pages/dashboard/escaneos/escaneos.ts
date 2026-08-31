import { Component, OnInit, HostListener, inject, signal, computed, DestroyRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpEventType } from '@angular/common/http';
import { PedidosService, PedidoResponse } from '../../../services/pedidos.service';
import { EscaneosService, EscaneoResponse } from '../../../services/escaneos.service';
import { Visor3dComponent } from './visor3d.component';
import { PedidoDetalleModalComponent } from '../pedidos/pedido-detalle-modal/pedido-detalle-modal.component';
import { iniciarPolling } from '../../../shared/poll.util';

const EXTENSIONES_3D = ['.stl', '.obj', '.ply', '.3ds', '.step', '.stp', '.iges', '.igs'];
// Formatos que el visor 3D embebido sabe renderizar.
const VISUALIZABLES_3D = ['.stl', '.obj'];

@Component({
  selector: 'app-escaneos',
  standalone: true,
  imports: [CommonModule, FormsModule, Visor3dComponent, PedidoDetalleModalComponent],
  templateUrl: './escaneos.html',
  styleUrls: ['./escaneos.css'],
})
export class EscaneosComponent implements OnInit {
  private pedidosService = inject(PedidosService);
  private escaneosService = inject(EscaneosService);
  private destroyRef = inject(DestroyRef);

  cargandoPedidos = signal(true);
  errorPedidos    = signal('');
  pedidos         = signal<PedidoResponse[]>([]);
  busqueda        = signal('');

  pedidoSeleccionado = signal<PedidoResponse | null>(null);
  escaneos           = signal<EscaneoResponse[]>([]);
  cargandoEscaneos   = signal(false);
  detalleAbiertoId   = signal<number | null>(null);
  subiendo           = signal(false);
  subidosCount       = signal(0);
  subiendoTotal      = signal(0);
  progresoActual     = signal(0);
  eliminandoId       = signal<number | null>(null);
  arrastrando        = signal(false);

  // Campo descripción para el upload
  descripcionInput = '';

  // Visor 3D
  visorAbierto    = signal(false);
  visorUrl        = signal('');
  visorFileName   = signal('');
  cargandoVisorId = signal<number | null>(null);
  descargandoId   = signal<number | null>(null);
  private visorObjectUrl: string | null = null;

  pedidosFiltrados = computed(() => {
    const q = this.busqueda().toLowerCase().trim();
    if (!q) return this.pedidos();
    return this.pedidos().filter(p =>
      p.nroPedido.toLowerCase().includes(q) ||
      p.paciente.toLowerCase().includes(q) ||
      p.odontologoNombre.toLowerCase().includes(q)
    );
  });

  /** Avisa antes de cerrar/refrescar si hay una subida en curso: si se navega, se pierde. */
  @HostListener('window:beforeunload', ['$event'])
  avisarSiHaySubidaEnCurso(event: BeforeUnloadEvent): void {
    if (this.subiendo()) event.preventDefault();
  }

  ngOnInit(): void {
    this.cargarPedidos();
    iniciarPolling(() => this.cargarPedidos(true), this.destroyRef);
  }

  private cargarPedidos(silencioso = false): void {
    if (silencioso && this.subiendo()) return; // no pisar la lista mientras se suben escaneos
    if (!silencioso) this.cargandoPedidos.set(true);
    this.pedidosService.listarTodos().subscribe({
      next: ps => {
        this.pedidos.set(ps.sort((a, b) =>
          new Date(b.fechaCreacion).getTime() - new Date(a.fechaCreacion).getTime()
        ));
        if (!silencioso) this.cargandoPedidos.set(false);
      },
      error: () => {
        if (!silencioso) {
          this.errorPedidos.set('No se pudieron cargar los pedidos.');
          this.cargandoPedidos.set(false);
        }
      },
    });
  }

  abrirDetallePedido(): void {
    const p = this.pedidoSeleccionado();
    if (p) this.detalleAbiertoId.set(p.id);
  }

  cerrarDetallePedido(): void {
    this.detalleAbiertoId.set(null);
  }

  seleccionar(p: PedidoResponse): void {
    this.pedidoSeleccionado.set(p);
    this.escaneos.set([]);
    this.cargandoEscaneos.set(true);
    this.escaneosService.listar(p.id).subscribe({
      next: es => { this.escaneos.set(es); this.cargandoEscaneos.set(false); },
      error: ()  => { this.cargandoEscaneos.set(false); },
    });
  }

  onFileInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files?.length) this.subirArchivos(Array.from(input.files));
    input.value = '';
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.arrastrando.set(false);
    const files = event.dataTransfer?.files;
    if (files?.length) this.subirArchivos(Array.from(files));
  }

  onDragOver(event: DragEvent): void { event.preventDefault(); this.arrastrando.set(true); }
  onDragLeave(): void { this.arrastrando.set(false); }

  /** Sube uno o varios archivos en secuencia. La descripción (si hay) se aplica a todos. */
  private subirArchivos(files: File[]): void {
    const p = this.pedidoSeleccionado();
    if (!p || files.length === 0) return;
    this.subiendo.set(true);
    this.subidosCount.set(0);
    this.subiendoTotal.set(files.length);
    const desc = this.descripcionInput.trim() || undefined;

    const subirSiguiente = (i: number): void => {
      if (i >= files.length) {
        this.descripcionInput = '';
        this.subiendo.set(false);
        this.progresoActual.set(0);
        return;
      }
      this.progresoActual.set(0);
      this.escaneosService.subir(p.id, files[i], desc).subscribe({
        next: event => {
          if (event.type === HttpEventType.UploadProgress && event.total) {
            this.progresoActual.set(Math.round((100 * event.loaded) / event.total));
          } else if (event.type === HttpEventType.Response && event.body) {
            this.escaneos.update(es => [event.body!, ...es]);
            this.subidosCount.set(i + 1);
            subirSiguiente(i + 1);
          }
        },
        error: () => {
          this.subidosCount.set(i + 1);
          subirSiguiente(i + 1);
        },
      });
    };
    subirSiguiente(0);
  }

  eliminar(escaneo: EscaneoResponse): void {
    const p = this.pedidoSeleccionado();
    if (!p) return;
    this.eliminandoId.set(escaneo.id);
    this.escaneosService.eliminar(p.id, escaneo.id).subscribe({
      next: () => {
        this.escaneos.update(es => es.filter(e => e.id !== escaneo.id));
        this.eliminandoId.set(null);
      },
      error: () => { this.eliminandoId.set(null); },
    });
  }

  /** ¿El formato se puede abrir en el visor 3D embebido? */
  esVisualizable3d(fileName: string): boolean {
    return VISUALIZABLES_3D.includes(this.extension(fileName));
  }

  /** Descarga el escaneo (blob autenticado) y abre el visor 3D con una object URL local. */
  verEscaneo(escaneo: EscaneoResponse): void {
    const p = this.pedidoSeleccionado();
    if (!p) return;
    this.cargandoVisorId.set(escaneo.id);
    this.escaneosService.descargar(p.id, escaneo.id).subscribe({
      next: blob => {
        this.visorObjectUrl = URL.createObjectURL(blob);
        this.visorUrl.set(this.visorObjectUrl);
        this.visorFileName.set(escaneo.fileName);
        this.visorAbierto.set(true);
        this.cargandoVisorId.set(null);
      },
      error: () => { this.cargandoVisorId.set(null); },
    });
  }

  cerrarVisor(): void {
    this.visorAbierto.set(false);
    this.visorUrl.set('');
    if (this.visorObjectUrl) {
      URL.revokeObjectURL(this.visorObjectUrl);
      this.visorObjectUrl = null;
    }
  }

  /** Descarga el archivo del escaneo al disco del usuario. */
  descargarEscaneo(escaneo: EscaneoResponse): void {
    const p = this.pedidoSeleccionado();
    if (!p) return;
    this.descargandoId.set(escaneo.id);
    this.escaneosService.descargar(p.id, escaneo.id).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = escaneo.fileName;
        a.click();
        setTimeout(() => URL.revokeObjectURL(url), 15000);
        this.descargandoId.set(null);
      },
      error: () => { this.descargandoId.set(null); },
    });
  }

  extension(fileName: string): string {
    const idx = fileName.lastIndexOf('.');
    return idx >= 0 ? fileName.substring(idx).toLowerCase() : '';
  }

  colorExt(fileName: string): string {
    const ext = this.extension(fileName);
    const m: Record<string, string> = {
      '.stl': 'ext-stl', '.obj': 'ext-obj', '.ply': 'ext-ply',
      '.3ds': 'ext-3ds', '.step': 'ext-step', '.stp': 'ext-step',
      '.iges': 'ext-iges', '.igs': 'ext-iges',
    };
    return m[ext] ?? 'ext-otro';
  }

  formatSize(bytes: number | null): string {
    if (!bytes) return '—';
    if (bytes < 1024)        return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }

  claseEstado(estado: string): string {
    const m: Record<string, string> = {
      RECIBIDO: 'est-recibido', EN_PROCESO: 'est-proceso',
      CONTROL: 'est-control',   LISTO: 'est-listo',
      ENTREGADO: 'est-entregado', CANCELADO: 'est-cancelado',
    };
    return m[estado] ?? '';
  }

  readonly accept = EXTENSIONES_3D.join(',');
}
