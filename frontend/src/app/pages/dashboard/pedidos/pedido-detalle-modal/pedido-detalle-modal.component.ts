import { Component, EventEmitter, Input, OnInit, Output, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { PedidosService, PedidoResponse, EstadoPedido } from '../../../../services/pedidos.service';
import { EscaneosService, EscaneoResponse } from '../../../../services/escaneos.service';
import { NotificationService } from '../../../../services/notification.service';
import { Visor3dComponent } from '../../escaneos/visor3d.component';

const VISUALIZABLES_3D = ['.stl', '.obj'];

/**
 * Modal de detalle "grande" de un pedido: toda su info + los escaneos 3D
 * adjuntos con preview. Es autocontenido (recibe solo el id y busca sus
 * propios datos), así se puede abrir desde cualquier pantalla que solo
 * tenga el nroPedido/id a mano (Escaneos, Entregas, Dashboard, ficha del
 * odontólogo) sin depender de que esa pantalla ya tenga el PedidoResponse
 * completo cargado.
 */
@Component({
  selector: 'app-pedido-detalle-modal',
  standalone: true,
  imports: [CommonModule, Visor3dComponent],
  templateUrl: './pedido-detalle-modal.component.html',
  styleUrls: ['./pedido-detalle-modal.component.css'],
})
export class PedidoDetalleModalComponent implements OnInit {
  @Input({ required: true }) pedidoId!: number;
  @Output() cerrar = new EventEmitter<void>();

  private pedidosService = inject(PedidosService);
  private escaneosService = inject(EscaneosService);
  private notif = inject(NotificationService);

  cargando = signal(true);
  error = signal('');
  pedido = signal<PedidoResponse | null>(null);

  escaneos = signal<EscaneoResponse[]>([]);
  cargandoEscaneos = signal(false);

  // Visor 3D embebido
  visorAbierto = signal(false);
  visorUrl = signal('');
  visorFileName = signal('');
  cargandoVisorId = signal<number | null>(null);
  descargandoId = signal<number | null>(null);
  private visorObjectUrl: string | null = null;

  ngOnInit(): void {
    this.pedidosService.buscarPorId(this.pedidoId).subscribe({
      next: p => {
        this.pedido.set(p);
        this.cargando.set(false);
        this.cargarEscaneos();
      },
      error: err => {
        this.cargando.set(false);
        this.error.set('No se pudo cargar el pedido.');
        this.notif.errorHttp(err, 'No se pudo cargar el pedido');
      },
    });
  }

  private cargarEscaneos(): void {
    this.cargandoEscaneos.set(true);
    this.escaneosService.listar(this.pedidoId).subscribe({
      next: es => { this.escaneos.set(es); this.cargandoEscaneos.set(false); },
      error: () => this.cargandoEscaneos.set(false),
    });
  }

  onCerrar(): void {
    if (this.visorAbierto()) { this.cerrarVisor(); return; }
    this.cerrar.emit();
  }

  // ── Escaneos 3D ──────────────────────────────────────────────────

  extension(fileName: string): string {
    const idx = fileName.lastIndexOf('.');
    return idx >= 0 ? fileName.substring(idx).toLowerCase() : '';
  }

  esVisualizable3d(fileName: string): boolean {
    return VISUALIZABLES_3D.includes(this.extension(fileName));
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

  verEscaneo(escaneo: EscaneoResponse): void {
    this.cargandoVisorId.set(escaneo.id);
    this.escaneosService.descargar(this.pedidoId, escaneo.id).subscribe({
      next: blob => {
        this.visorObjectUrl = URL.createObjectURL(blob);
        this.visorUrl.set(this.visorObjectUrl);
        this.visorFileName.set(escaneo.fileName);
        this.visorAbierto.set(true);
        this.cargandoVisorId.set(null);
      },
      error: err => { this.cargandoVisorId.set(null); this.notif.errorHttp(err, 'No se pudo abrir el escaneo'); },
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

  descargarEscaneo(escaneo: EscaneoResponse): void {
    this.descargandoId.set(escaneo.id);
    this.escaneosService.descargar(this.pedidoId, escaneo.id).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = escaneo.fileName;
        a.click();
        setTimeout(() => URL.revokeObjectURL(url), 15000);
        this.descargandoId.set(null);
      },
      error: err => { this.descargandoId.set(null); this.notif.errorHttp(err, 'No se pudo descargar el escaneo'); },
    });
  }

  // ── Formato ──────────────────────────────────────────────────────

  formatFecha(iso: string | null): string {
    if (!iso) return '—';
    return new Date(iso).toLocaleDateString('es-AR', { day: '2-digit', month: '2-digit', year: 'numeric' });
  }

  formatFechaHora(iso: string | null): string {
    if (!iso) return '—';
    return new Date(iso).toLocaleString('es-AR', {
      day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit',
    });
  }

  formatPrecio(n: number | null): string {
    if (n == null) return 'A convenir';
    return new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 0 }).format(n);
  }

  labelEstado(e: EstadoPedido): string {
    const m: Record<EstadoPedido, string> = {
      RECIBIDO: 'Recibido', EN_PROCESO: 'En proceso',
      CONTROL: 'Control', LISTO: 'Listo',
      ENTREGADO: 'Entregado', CANCELADO: 'Cancelado',
    };
    return m[e] ?? e;
  }

  colorEstado(e: EstadoPedido): string {
    const m: Record<EstadoPedido, string> = {
      RECIBIDO: 'blue', EN_PROCESO: 'amber',
      CONTROL: 'purple', LISTO: 'green',
      ENTREGADO: 'cyan', CANCELADO: 'rose',
    };
    return m[e] ?? 'muted';
  }
}
