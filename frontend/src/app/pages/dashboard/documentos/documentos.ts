import { Component, OnInit, OnDestroy, inject, signal, computed, DestroyRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { PedidosService, PedidoResponse } from '../../../services/pedidos.service';
import { DocumentosService, DocumentoResponse } from '../../../services/documentos.service';
import { AuthService } from '../../../services/auth';
import { iniciarPolling } from '../../../shared/poll.util';
import { EscaneosComponent } from '../escaneos/escaneos';

@Component({
  selector: 'app-documentos',
  standalone: true,
  imports: [CommonModule, FormsModule, EscaneosComponent],
  templateUrl: './documentos.html',
  styleUrls: ['./documentos.css'],
})
export class DocumentosComponent implements OnInit, OnDestroy {
  private pedidosService = inject(PedidosService);
  private docService      = inject(DocumentosService);
  private auth            = inject(AuthService);
  private destroyRef      = inject(DestroyRef);

  readonly esAdmin = this.auth.isAdmin();

  // ── Vista activa: documentos por pedido | escaneos 3D ─────────────
  vista = signal<'pedidos' | 'escaneos'>('pedidos');

  // ── Estado general ──────────────────────────────────────────────────────
  cargandoPedidos = signal(true);
  errorPedidos    = signal('');
  pedidos         = signal<PedidoResponse[]>([]);
  busqueda        = signal('');

  pedidoSeleccionado = signal<PedidoResponse | null>(null);
  docs               = signal<DocumentoResponse[]>([]);
  cargandoDocs       = signal(false);
  subiendo           = signal(false);
  eliminandoId       = signal<number | null>(null);
  abriendoId         = signal<number | null>(null);

  /** Object URLs de las imágenes ya descargadas, para mostrarlas como thumbnail. */
  imagenUrls = signal<Map<number, string>>(new Map());

  // ── Upload drag & drop ──────────────────────────────────────────────────
  arrastrando = signal(false);

  // ── Computed ────────────────────────────────────────────────────────────
  pedidosFiltrados = computed(() => {
    const q = this.busqueda().toLowerCase().trim();
    if (!q) return this.pedidos();
    return this.pedidos().filter(p =>
      p.nroPedido.toLowerCase().includes(q) ||
      p.paciente.toLowerCase().includes(q) ||
      p.odontologoNombre.toLowerCase().includes(q) ||
      p.trabajo.toLowerCase().includes(q)
    );
  });

  ngOnInit(): void {
    this.cargarPedidos();
    iniciarPolling(() => this.cargarPedidos(true), this.destroyRef);
  }

  private cargarPedidos(silencioso = false): void {
    if (silencioso && this.subiendo()) return; // no pisar la lista mientras se sube un archivo
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

  // ── Vista ──────────────────────────────────────────────────────────────

  cambiarVista(v: 'pedidos' | 'escaneos'): void {
    this.vista.set(v);
  }

  seleccionar(p: PedidoResponse): void {
    this.pedidoSeleccionado.set(p);
    this.docs.set([]);
    this.revocarImagenUrls();
    this.cargandoDocs.set(true);
    this.docService.listar(p.id).subscribe({
      next: ds => {
        this.docs.set(ds);
        this.cargandoDocs.set(false);
        ds.filter(d => this.esImagen(d)).forEach(d => this.cargarThumbnail(p.id, d));
      },
      error: ()  => { this.cargandoDocs.set(false); },
    });
  }

  /** Descarga la imagen y la guarda como object URL para mostrarla de thumbnail. */
  private cargarThumbnail(pedidoId: number, doc: DocumentoResponse): void {
    this.docService.descargar(pedidoId, doc.id).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        this.imagenUrls.update(m => new Map(m).set(doc.id, url));
      },
      error: () => { /* sin thumbnail si falla; se puede seguir viendo con el botón */ },
    });
  }

  private revocarImagenUrls(): void {
    this.imagenUrls().forEach(url => URL.revokeObjectURL(url));
    this.imagenUrls.set(new Map());
  }

  ngOnDestroy(): void {
    this.revocarImagenUrls();
  }

  /** Abre el documento en una pestaña nueva (reusa el thumbnail si ya se descargó). */
  verDoc(doc: DocumentoResponse): void {
    const yaDescargado = this.imagenUrls().get(doc.id);
    if (yaDescargado) { window.open(yaDescargado, '_blank'); return; }

    const p = this.pedidoSeleccionado();
    if (!p) return;
    this.abriendoId.set(doc.id);
    this.docService.descargar(p.id, doc.id).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        window.open(url, '_blank');
        this.abriendoId.set(null);
        setTimeout(() => URL.revokeObjectURL(url), 15000);
      },
      error: () => { this.abriendoId.set(null); },
    });
  }

  // ── Upload ──────────────────────────────────────────────────────────────

  onFileInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files?.length) this.subirArchivo(input.files[0]);
    input.value = '';
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.arrastrando.set(false);
    const file = event.dataTransfer?.files?.[0];
    if (file) this.subirArchivo(file);
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.arrastrando.set(true);
  }

  onDragLeave(): void {
    this.arrastrando.set(false);
  }

  private subirArchivo(file: File): void {
    const p = this.pedidoSeleccionado();
    if (!p) return;
    this.subiendo.set(true);
    this.docService.subir(p.id, file).subscribe({
      next: doc => {
        this.docs.update(ds => [doc, ...ds]);
        this.subiendo.set(false);
        if (this.esImagen(doc)) this.cargarThumbnail(p.id, doc);
      },
      error: () => { this.subiendo.set(false); },
    });
  }

  // ── Eliminar ─────────────────────────────────────────────────────────────

  eliminar(doc: DocumentoResponse): void {
    const p = this.pedidoSeleccionado();
    if (!p) return;
    this.eliminandoId.set(doc.id);
    this.docService.eliminar(p.id, doc.id).subscribe({
      next: () => {
        this.docs.update(ds => ds.filter(d => d.id !== doc.id));
        this.eliminandoId.set(null);
        const url = this.imagenUrls().get(doc.id);
        if (url) {
          URL.revokeObjectURL(url);
          this.imagenUrls.update(m => { const n = new Map(m); n.delete(doc.id); return n; });
        }
      },
      error: () => { this.eliminandoId.set(null); },
    });
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  esImagen(doc: DocumentoResponse): boolean {
    const ct = doc.contentType ?? '';
    return ct.startsWith('image/');
  }

  esPdf(doc: DocumentoResponse): boolean {
    return (doc.contentType ?? '').includes('pdf');
  }

  formatSize(bytes: number | null): string {
    if (!bytes) return '—';
    if (bytes < 1024)       return bytes + ' B';
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
}
