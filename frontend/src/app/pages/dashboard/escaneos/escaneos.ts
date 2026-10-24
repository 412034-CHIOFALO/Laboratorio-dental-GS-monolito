import { Component, OnInit, HostListener, inject, signal, computed, DestroyRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpEventType } from '@angular/common/http';
import { PedidosService, PedidoResponse } from '../../../services/pedidos.service';
import { EscaneosService, EscaneoResponse } from '../../../services/escaneos.service';
import { Visor3dComponent } from './visor3d.component';
import { PedidoDetalleModalComponent } from '../pedidos/pedido-detalle-modal/pedido-detalle-modal.component';
import { iniciarPolling } from '../../../shared/poll.util';
import { NotificationService } from '../../../services/notification.service';

const EXTENSIONES_3D = ['.stl', '.obj', '.ply', '.3ds', '.step', '.stp', '.iges', '.igs'];
// Formatos que el visor 3D embebido sabe renderizar.
const VISUALIZABLES_3D = ['.stl', '.obj', '.ply'];

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
  private notif = inject(NotificationService);

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
      error: err => {
        this.cargandoEscaneos.set(false);
        this.notif.errorHttp(err, 'No se pudieron cargar los escaneos de este pedido');
      },
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
    let subidos = 0;
    let rechazados = 0;

    const subirSiguiente = (i: number): void => {
      if (i >= files.length) {
        this.descripcionInput = '';
        this.subiendo.set(false);
        this.progresoActual.set(0);
        this.avisarResultadoDeSubida(files.length, subidos, rechazados);
        return;
      }
      this.progresoActual.set(0);
      this.escaneosService.subir(p.id, files[i], desc).subscribe({
        next: event => {
          if (event.type === HttpEventType.UploadProgress && event.total) {
            this.progresoActual.set(Math.round((100 * event.loaded) / event.total));
          } else if (event.type === HttpEventType.Response && event.body) {
            subidos++;
            this.escaneos.update(es => [event.body!, ...es]);
            this.subidosCount.set(i + 1);
            this.avisarAdvertencias(files[i].name, event.body.advertencias);
            subirSiguiente(i + 1);
          }
        },
        error: err => {
          // El backend dice por qué rechazó el archivo (cortado, con datos inválidos, demasiado pesado…): se muestra tal cual.
          rechazados++;
          this.notif.error(`${files[i].name}: ${this.notif.mensajeHttp(err, 'no se pudo subir')}`, 'Escaneo rechazado');
          this.subidosCount.set(i + 1);
          subirSiguiente(i + 1);
        },
      });
    };
    subirSiguiente(0);
  }

  /** Lo que el backend vio raro pero dejó pasar (unidades dudosas, caras degeneradas…): hay que enterarse al subir, no después. */
  private avisarAdvertencias(nombre: string, advertencias: string[] | null | undefined): void {
    if (!advertencias?.length) return;
    const resto = advertencias.length > 1 ? ` (y ${advertencias.length - 1} aviso${advertencias.length > 2 ? 's' : ''} más)` : '';
    this.notif.alerta(`${nombre}: ${advertencias[0]}${resto}`, 'Se subió, pero revisalo');
  }

  private avisarResultadoDeSubida(total: number, subidos: number, rechazados: number): void {
    if (rechazados === 0 && subidos > 0) {
      this.notif.exito(subidos === 1 ? 'Escaneo subido' : `${subidos} escaneos subidos`);
    } else if (subidos > 0 && rechazados > 0) {
      this.notif.alerta(`Se subieron ${subidos} de ${total} escaneos. ${rechazados === 1 ? 'Uno fue rechazado' : `${rechazados} fueron rechazados`}.`, 'Subida incompleta');
    }
  }

  eliminar(escaneo: EscaneoResponse): void {
    const p = this.pedidoSeleccionado();
    if (!p) return;
    this.eliminandoId.set(escaneo.id);
    this.escaneosService.eliminar(p.id, escaneo.id).subscribe({
      next: () => {
        this.escaneos.update(es => es.filter(e => e.id !== escaneo.id));
        this.eliminandoId.set(null);
        this.notif.exito('Escaneo eliminado');
      },
      error: err => {
        this.eliminandoId.set(null);
        this.notif.errorHttp(err, `No se pudo eliminar ${escaneo.fileName}`);
      },
    });
  }

  /** ¿El formato se puede abrir en el visor 3D embebido? */
  /** "STL binario · 12.340 triángulos · 62 × 48 × 31 mm" — lo que el backend midió al subir; vacío en escaneos viejos. */
  detalleMalla(e: EscaneoResponse): string {
    if (!e.formato) return '';
    const formato = e.formato.replace('_', ' ').replace('BINARIO', 'binario').replace('ASCII', 'ASCII');
    const partes = [formato];
    if (e.triangulos != null) partes.push(`${e.triangulos.toLocaleString('es-AR')} triángulos`);
    if (e.dimXMm != null && e.dimYMm != null && e.dimZMm != null) {
      const m = (n: number) => n.toLocaleString('es-AR', { maximumFractionDigits: 1 });
      partes.push(`${m(e.dimXMm)} × ${m(e.dimYMm)} × ${m(e.dimZMm)} mm`);
    }
    return partes.join(' · ');
  }

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
      error: err => {
        this.cargandoVisorId.set(null);
        this.notif.errorHttp(err, `No se pudo abrir ${escaneo.fileName}`);
      },
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
      error: err => {
        this.descargandoId.set(null);
        this.notif.errorHttp(err, `No se pudo descargar ${escaneo.fileName}`);
      },
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
