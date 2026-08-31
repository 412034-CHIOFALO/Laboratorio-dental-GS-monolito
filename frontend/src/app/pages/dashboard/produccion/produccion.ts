import { Component, OnInit, inject, DestroyRef } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { PedidosService, PedidoResponse, EstadoPedido as EstadoPedidoBackend } from '../../../services/pedidos.service';
import { NotificationService } from '../../../services/notification.service';
import { iniciarPolling } from '../../../shared/poll.util';

/** Solo los 4 estados que el Kanban maneja (RECIBIDO → LISTO). */
export type EstadoKanban = 'RECIBIDO' | 'EN_PROCESO' | 'CONTROL' | 'LISTO';

/** Modelo local de la card. Mapea PedidoResponse para que el HTML quede simple. */
export interface PedidoKanban {
  id: number;
  nroPedido: string;
  odontologo: string;
  paciente: string;
  trabajo: string;
  tecnico: string;
  fechaEntrega: Date | null;
  estado: EstadoKanban;
  prioridad: 'NORMAL' | 'URGENTE';
}

interface Columna {
  estado: EstadoKanban;
  labelLargo: string;
  labelCorto: string;
  accionLabel: string;
}

@Component({
  selector: 'app-produccion',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './produccion.html',
  styleUrls: ['./produccion.css'],
})
export class ProduccionComponent implements OnInit {

  private notif = inject(NotificationService);
  private destroyRef = inject(DestroyRef);

  pedidos: PedidoKanban[] = [];
  tecnicoFiltro = 'TODOS';
  loading = false;
  error = '';

  // Mobile state
  activeTab: EstadoKanban = 'RECIBIDO';

  // Desktop drag state
  draggingPedido: PedidoKanban | null = null;
  dragOverColumn: EstadoKanban | null = null;

  readonly columnas: Columna[] = [
    { estado: 'RECIBIDO',   labelLargo: 'Recibido',           labelCorto: 'Recibido',  accionLabel: 'Iniciar producción' },
    { estado: 'EN_PROCESO', labelLargo: 'En proceso',         labelCorto: 'En proc.',  accionLabel: 'Enviar a control'   },
    { estado: 'CONTROL',    labelLargo: 'Control de calidad', labelCorto: 'Control',   accionLabel: 'Marcar como listo'  },
    { estado: 'LISTO',      labelLargo: 'Listo para entregar',labelCorto: 'P/Entregar',accionLabel: ''                   },
  ];

  readonly ordenEstados: EstadoKanban[] = ['RECIBIDO', 'EN_PROCESO', 'CONTROL', 'LISTO'];

  constructor(private pedidosService: PedidosService) {}

  ngOnInit() {
    this.cargar();
    iniciarPolling(() => this.cargar(true), this.destroyRef);
  }

  // Mapea PedidoResponse del backend al modelo local del Kanban
  private mapear(p: PedidoResponse): PedidoKanban {
    return {
      id:           p.id,
      nroPedido:    p.nroPedido,
      odontologo:   p.odontologoNombre,
      paciente:     p.paciente,
      trabajo:      p.trabajo,
      tecnico:      p.tecnicoNombre ?? 'Sin asignar',
      fechaEntrega: p.fechaEntrega ? new Date(p.fechaEntrega + 'T00:00:00') : null,
      estado:       p.estado as EstadoKanban,
      prioridad:    p.prioridad,
    };
  }

  /** True si el pedido está en alguno de los 4 estados del kanban. */
  private esKanbanEstado(estado: EstadoPedidoBackend): boolean {
    return estado === 'RECIBIDO' || estado === 'EN_PROCESO'
        || estado === 'CONTROL'  || estado === 'LISTO';
  }

  private cargar(silencioso = false) {
    // No pisar el tablero mientras se está arrastrando una tarjeta.
    if (silencioso && this.draggingPedido) return;
    if (!silencioso) { this.loading = true; this.error = ''; }
    this.pedidosService.listarTodos().subscribe({
      next: data => {
        this.pedidos = data
          .filter(p => this.esKanbanEstado(p.estado))
          .map(p => this.mapear(p));
        if (!silencioso) this.loading = false;
      },
      error: err => {
        if (!silencioso) {
          this.error = 'No se pudo cargar el tablero. Verificá que ms-pedidos esté corriendo.';
          this.loading = false;
        }
        console.error('Error al cargar kanban:', err);
      }
    });
  }

  // Getters
  get tecnicos(): string[] {
    const ts = this.pedidos
      .map(p => p.tecnico)
      .filter(t => t && t !== 'Sin asignar');
    return [...new Set(ts)].sort();
  }

  get totalActivos(): number {
    return this.pedidos.filter(p => p.estado !== 'LISTO').length;
  }

  get columnaActiva(): Columna {
    return this.columnas.find(c => c.estado === this.activeTab)!;
  }

  get urgentesActivos(): number {
    return this.pedidos.filter(p => p.prioridad === 'URGENTE' && p.estado !== 'LISTO').length;
  }

  pedidosPorEstado(estado: EstadoKanban): PedidoKanban[] {
    return this.pedidos.filter(p => {
      if (p.estado !== estado) return false;
      if (this.tecnicoFiltro !== 'TODOS' && p.tecnico !== this.tecnicoFiltro) return false;
      return true;
    });
  }

  // Mobile: tab navigation
  setActiveTab(estado: EstadoKanban) {
    this.activeTab = estado;
  }

  // Avanzar al siguiente estado — llama al backend de pedidos
  avanzar(pedido: PedidoKanban) {
    const siguiente = this.columnSiguiente(pedido.estado);
    if (!siguiente) return;
    this.cambiarEstado(pedido, siguiente, 'Movido a');
  }

  // Retroceder al estado anterior
  retroceder(pedido: PedidoKanban) {
    const anterior = this.columnAnterior(pedido.estado);
    if (!anterior) return;
    this.cambiarEstado(pedido, anterior, 'Devuelto a');
  }

  /** Cambio optimista: actualiza la UI y revierte si falla. */
  private cambiarEstado(pedido: PedidoKanban, nuevo: EstadoKanban, prefijoToast: string) {
    const estadoAnterior = pedido.estado;
    pedido.estado = nuevo;
    this.pedidosService.actualizarEstado(pedido.id, nuevo).subscribe({
      next: res => {
        pedido.estado = res.estado as EstadoKanban;
        const label = this.columnas.find(c => c.estado === nuevo)?.labelLargo ?? nuevo;
        if (nuevo === 'LISTO') {
          this.notif.exito(`${pedido.nroPedido} listo para entregar`, '¡Trabajo terminado!');
        } else {
          this.notif.info(`${pedido.nroPedido} → ${prefijoToast} "${label}"`);
        }
      },
      error: err => {
        pedido.estado = estadoAnterior;
        this.notif.errorHttp(err, 'No se pudo actualizar el estado');
      }
    });
  }

  columnSiguiente(estado: EstadoKanban): EstadoKanban | null {
    const idx = this.ordenEstados.indexOf(estado);
    return idx < this.ordenEstados.length - 1 ? this.ordenEstados[idx + 1] : null;
  }

  columnAnterior(estado: EstadoKanban): EstadoKanban | null {
    const idx = this.ordenEstados.indexOf(estado);
    return idx > 0 ? this.ordenEstados[idx - 1] : null;
  }

  // Desktop drag & drop
  onDragStart(event: DragEvent, pedido: PedidoKanban) {
    this.draggingPedido = pedido;
    if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move';
  }

  onDragOver(event: DragEvent, estado: EstadoKanban) {
    event.preventDefault();
    if (event.dataTransfer) event.dataTransfer.dropEffect = 'move';
    this.dragOverColumn = estado;
  }

  onDragLeave(event: DragEvent) {
    const target  = event.currentTarget as HTMLElement;
    const related = event.relatedTarget as Node | null;
    if (!related || !target.contains(related)) this.dragOverColumn = null;
  }

  onDrop(event: DragEvent, nuevoEstado: EstadoKanban) {
    event.preventDefault();
    this.dragOverColumn = null;
    const pedido = this.draggingPedido;
    this.draggingPedido = null;

    if (!pedido || pedido.estado === nuevoEstado) return;
    this.cambiarEstado(pedido, nuevoEstado, 'Movido a');
  }

  onDragEnd() {
    this.draggingPedido = null;
    this.dragOverColumn = null;
  }

  // Helpers de fecha y UI
  diasRestantes(fecha: Date | null): number {
    if (!fecha) return 999;
    const hoy = new Date();
    hoy.setHours(0, 0, 0, 0);
    const t = new Date(fecha);
    t.setHours(0, 0, 0, 0);
    return Math.ceil((t.getTime() - hoy.getTime()) / 86400000);
  }

  diasLabel(fecha: Date | null): string {
    if (!fecha) return 'Sin fecha';
    const d = this.diasRestantes(fecha);
    if (d < 0)   return `Atrasado ${Math.abs(d)}d`;
    if (d === 0) return 'Entrega hoy';
    if (d === 1) return 'Entrega mañana';
    return `Entrega en ${d}d`;
  }

  diasClass(fecha: Date | null): string {
    if (!fecha) return 'ok';
    const d = this.diasRestantes(fecha);
    if (d < 0)  return 'overdue';
    if (d <= 1) return 'urgent';
    if (d <= 4) return 'soon';
    return 'ok';
  }

  tecnicoColor(nombre: string): string {
    const colors = ['#3b82f6', '#8b5cf6', '#f59e0b', '#14b8a6', '#ec4899'];
    let h = 0;
    for (const c of nombre) h = (h * 31 + c.charCodeAt(0)) % colors.length;
    return colors[h];
  }

  tecnicoInitials(nombre: string): string {
    return nombre.split(' ').slice(0, 2).map(p => p[0]).join('').toUpperCase();
  }
}
