import { Component, OnInit, inject, DestroyRef } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { PedidosService, PedidoResponse, EntregaRequest } from '../../../services/pedidos.service';
import { OdontologosService, OdontologoResponse } from '../../../services/odontologos.service';
import { NotificationService } from '../../../services/notification.service';
import { fechaLocal, hoyComoLocalDate } from '../../../services/date-utils';
import { PedidoDetalleModalComponent } from '../pedidos/pedido-detalle-modal/pedido-detalle-modal.component';
import { iniciarPolling } from '../../../shared/poll.util';
import { TableSort } from '../../../shared/table-sort';

type Tab = 'PENDIENTES' | 'HISTORIAL';

@Component({
  selector: 'app-entregas',
  standalone: true,
  imports: [FormsModule, PedidoDetalleModalComponent],
  templateUrl: './entregas.html',
  styleUrls: ['./entregas.css'],
})
export class EntregasComponent implements OnInit {

  tabActiva: Tab = 'PENDIENTES';
  pendientes: PedidoResponse[] = [];
  historial: PedidoResponse[] = [];
  loading = false;
  error = '';

  readonly sort = new TableSort<PedidoResponse>('fechaEntregaReal', 'desc');
  /** Historial ordenado para la tabla. */
  get historialVista(): PedidoResponse[] {
    return this.sort.aplicar(this.historial);
  }

  // Modal de detalle del pedido (reusable, autocontenido)
  detalleAbiertoId: number | null = null;

  // Modal "Marcar entregado"
  showModalEntregar = false;
  saving = false;
  pedidoAEntregar: PedidoResponse | null = null;

  formEntrega: {
    retiradoPor: string;
    fechaEntregaReal: string;
    observacionesEntrega: string;
    monto: number | null;
  } = this.formVacio();

  // Mensaje WhatsApp del recorrido del día
  mensajeCopiado = false;

  /** Cache id → odontólogo para resolver dirección/clínica/teléfono al armar el mensaje. */
  private odontologosCache = new Map<number, OdontologoResponse>();

  private notif = inject(NotificationService);
  private destroyRef = inject(DestroyRef);

  constructor(
    private pedidosService: PedidosService,
    private odontologosService: OdontologosService,
  ) {}

  ngOnInit(): void {
    this.cargar();
    this.cargarOdontologos();
    iniciarPolling(() => this.cargar(true), this.destroyRef);
  }

  abrirDetalle(pedidoId: number): void {
    this.detalleAbiertoId = pedidoId;
  }

  cerrarDetalle(): void {
    this.detalleAbiertoId = null;
  }

  /** Pre-carga los odontólogos activos en cache para usar en el mensaje WhatsApp. */
  private cargarOdontologos(): void {
    this.odontologosService.buscar().subscribe({
      next: data => {
        this.odontologosCache.clear();
        data.forEach(o => this.odontologosCache.set(o.id, o));
      },
      error: err => console.error('No se pudo cargar el directorio de odontólogos:', err),
    });
  }

  // ─────────────────────────────────────────────────────────────
  // CARGA
  // ─────────────────────────────────────────────────────────────

  private cargar(silencioso = false): void {
    if (!silencioso) { this.loading = true; this.error = ''; }
    this.pedidosService.listarTodos().subscribe({
      next: data => {
        this.pendientes = data
          .filter(p => p.estado === 'LISTO')
          .sort(this.ordenarPendientes);
        this.historial = data
          .filter(p => p.estado === 'ENTREGADO')
          .sort((a, b) => this.tsEntrega(b) - this.tsEntrega(a))
          .slice(0, 30); // últimos 30 entregados
        if (!silencioso) this.loading = false;
      },
      error: err => {
        if (!silencioso) {
          this.error = 'No se pudieron cargar las entregas. ¿ms-pedidos está corriendo?';
          this.loading = false;
        }
        console.error(err);
      },
    });
  }

  /** URGENTES primero, después por fecha de entrega ascendente. */
  private ordenarPendientes = (a: PedidoResponse, b: PedidoResponse): number => {
    if (a.prioridad !== b.prioridad) return a.prioridad === 'URGENTE' ? -1 : 1;
    return fechaLocal(a.fechaEntrega).getTime() - fechaLocal(b.fechaEntrega).getTime();
  };

  private tsEntrega(p: PedidoResponse): number {
    return new Date(p.fechaEntregaReal ?? p.fechaUltimaModificacion).getTime();
  }

  // ─────────────────────────────────────────────────────────────
  // STATS DEL HEADER
  // ─────────────────────────────────────────────────────────────

  get totalPendientes(): number { return this.pendientes.length; }
  get urgentesPendientes(): number { return this.pendientes.filter(p => p.prioridad === 'URGENTE').length; }
  get vencidasPendientes(): number {
    const hoy = new Date(); hoy.setHours(0, 0, 0, 0);
    return this.pendientes.filter(p => fechaLocal(p.fechaEntrega) < hoy).length;
  }
  get entregadasHoy(): number {
    const hoyStr = hoyComoLocalDate();
    return this.historial.filter(p => p.fechaEntregaReal === hoyStr).length;
  }

  // ─────────────────────────────────────────────────────────────
  // MODAL MARCAR ENTREGADO
  // ─────────────────────────────────────────────────────────────

  abrirModalEntregar(p: PedidoResponse): void {
    this.pedidoAEntregar = p;
    this.formEntrega = this.formVacio();
    // Sugerir el nombre del odontólogo por defecto (el caso más común)
    this.formEntrega.retiradoPor = p.odontologoNombre;
    // Pre-cargar el monto a facturar con el precio acordado del pedido.
    this.formEntrega.monto = p.precioAcordado ?? null;
    this.showModalEntregar = true;
  }

  cerrarModalEntregar(): void {
    this.showModalEntregar = false;
    this.pedidoAEntregar = null;
  }

  private formVacio() {
    return {
      retiradoPor: '',
      fechaEntregaReal: hoyComoLocalDate(),
      observacionesEntrega: '',
      monto: null as number | null,
    };
  }

  get formEntregaValido(): boolean {
    return !!this.formEntrega.retiradoPor.trim()
        && this.formEntrega.monto != null
        && this.formEntrega.monto > 0;
  }

  confirmarEntrega(): void {
    if (!this.pedidoAEntregar || !this.formEntregaValido) return;
    this.saving = true;

    const request: EntregaRequest = {
      retiradoPor: this.formEntrega.retiradoPor.trim(),
      fechaEntregaReal: this.formEntrega.fechaEntregaReal,
      observacionesEntrega: this.formEntrega.observacionesEntrega?.trim() || null,
      monto: this.formEntrega.monto,
    };

    this.pedidosService.marcarEntregado(this.pedidoAEntregar.id, request).subscribe({
      next: res => {
        this.pendientes = this.pendientes.filter(p => p.id !== res.id);
        this.historial.unshift(res);
        this.saving = false;
        this.cerrarModalEntregar();
        this.notif.exito(`${res.nroPedido} entregado a ${res.retiradoPor}`);
      },
      error: err => {
        this.saving = false;
        this.notif.errorHttp(err, 'No se pudo marcar como entregado');
      },
    });
  }

  // ─────────────────────────────────────────────────────────────
  // MENSAJE WHATSAPP DEL RECORRIDO DEL DÍA
  // ─────────────────────────────────────────────────────────────

  get mensajeWhatsApp(): string {
    const hoy = new Date().toLocaleDateString('es-AR',
      { day: '2-digit', month: '2-digit', year: 'numeric' });

    if (this.pendientes.length === 0) {
      return `📦 *Entregas G&S — ${hoy}*\n\nNo hay entregas pendientes hoy. ✅`;
    }

    // Agrupar por odontologoId para preservar la relación con la ficha completa
    const porOdontologo = new Map<number, PedidoResponse[]>();
    for (const p of this.pendientes) {
      const lista = porOdontologo.get(p.odontologoId) ?? [];
      lista.push(p);
      porOdontologo.set(p.odontologoId, lista);
    }

    let msg = `📦 *Entregas G&S — ${hoy}*\n\n`;
    let n = 1;
    porOdontologo.forEach((pedidos, odontologoId) => {
      const primero = pedidos[0];
      const odontologo = this.odontologosCache.get(odontologoId);

      msg += `${n++}️⃣ *${primero.odontologoNombre}*\n`;

      // Datos de contacto/dirección — clave para la cadetería
      if (odontologo?.clinica)   msg += `   🏥 ${odontologo.clinica}\n`;
      if (odontologo?.direccion) msg += `   📍 ${odontologo.direccion}\n`;
      if (odontologo?.telefono)  msg += `   📞 ${odontologo.telefono}\n`;

      // Lista de trabajos a entregar
      for (const p of pedidos) {
        const urg = p.prioridad === 'URGENTE' ? '⚡ ' : '';
        msg += `   🦷 ${urg}${p.trabajo} — Pac: ${p.paciente}\n`;
      }
      msg += '\n';
    });
    msg += `📊 Total: ${this.pendientes.length} entrega${this.pendientes.length !== 1 ? 's' : ''} pendiente${this.pendientes.length !== 1 ? 's' : ''}`;
    return msg;
  }

  copiarMensaje(): void {
    navigator.clipboard.writeText(this.mensajeWhatsApp).then(() => {
      this.mensajeCopiado = true;
      this.notif.info('Mensaje copiado al portapapeles');
      setTimeout(() => (this.mensajeCopiado = false), 2500);
    });
  }

  abrirWhatsApp(): void {
    const url = `https://wa.me/?text=${encodeURIComponent(this.mensajeWhatsApp)}`;
    window.open(url, '_blank');
  }

  // ─────────────────────────────────────────────────────────────
  // HELPERS DE VISTA
  // ─────────────────────────────────────────────────────────────

  diasEsperando(p: PedidoResponse): { texto: string; clase: string } {
    const hoy = new Date(); hoy.setHours(0, 0, 0, 0);
    const fe = fechaLocal(p.fechaEntrega); fe.setHours(0, 0, 0, 0);
    const dias = Math.round((hoy.getTime() - fe.getTime()) / 86_400_000);
    if (dias > 0)  return { texto: `Vencido hace ${dias}d`, clase: 'vencido' };
    if (dias === 0) return { texto: 'Para hoy',              clase: 'urgente' };
    if (dias === -1) return { texto: 'Para mañana',          clase: 'pronto'  };
    return { texto: `En ${Math.abs(dias)}d`, clase: 'normal' };
  }

  formatFecha(iso: string | null): string {
    if (!iso) return '—';
    return new Date(iso).toLocaleDateString('es-AR', { day: '2-digit', month: '2-digit', year: 'numeric' });
  }

  formatPrecio(n: number | null): string {
    if (n == null) return 'A convenir';
    return new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 0 }).format(n);
  }
}
