import { Component, OnInit, inject, DestroyRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { PedidosService, PedidoResponse, EstadoPedido } from '../../../services/pedidos.service';
import { StockService, MaterialResponse } from '../../../services/stock.service';
import { FinanzasService, ResumenCajasResponse } from '../../../services/finanzas.service';
import { AuthService } from '../../../services/auth';
import { fechaLocal } from '../../../services/date-utils';
import { PedidoDetalleModalComponent } from '../pedidos/pedido-detalle-modal/pedido-detalle-modal.component';
import { iniciarPolling } from '../../../shared/poll.util';

interface KpiCard {
  label: string;
  value: string;
  delta?: { texto: string; clase: 'up' | 'down' | 'flat' };
  hue: string;            // color del dot indicador
  sparkline: number[];
  sparkColor: string;
  to?: string;
}

interface AlertaItem {
  hue: string;
  titulo: string;
  subtitulo: string;
  valor?: string;
  badge?: 'URGENTE' | 'CRITICO';
  to?: string;
}

@Component({
  selector: 'app-dashboard-home',
  standalone: true,
  imports: [CommonModule, RouterLink, PedidoDetalleModalComponent],
  templateUrl: './dashboard-home.html',
  styleUrls: ['./dashboard-home.css'],
})
export class DashboardHomeComponent implements OnInit {

  private pedidosService = inject(PedidosService);
  private stockService = inject(StockService);
  private finanzasService = inject(FinanzasService);
  private auth = inject(AuthService);
  private router = inject(Router);
  private destroyRef = inject(DestroyRef);

  detalleAbiertoId: number | null = null;

  loading = true;

  // Data crudo
  pedidos: PedidoResponse[] = [];
  materiales: MaterialResponse[] = [];
  resumenCajas: ResumenCajasResponse | null = null;

  // Derivado
  kpis: KpiCard[] = [];
  pedidosRecientes: PedidoResponse[] = [];
  alertas: AlertaItem[] = [];

  fechaHoy = this.formatearFechaLarga(new Date());

  ngOnInit(): void {
    this.cargar();
    iniciarPolling(() => this.cargar(true), this.destroyRef);
  }

  private cargar(silencioso = false): void {
    if (!silencioso) this.loading = true;
    // El resumen de cajas es ADMIN/ADMINISTRATIVO en el backend (403 para el resto).
    // Si el rol no lo ve ni pedimos el endpoint: un 403 acá tira abajo TODO el forkJoin
    // y el interceptor global redirige a /sin-permisos, bloqueando el dashboard entero
    // (técnicos, etc. quedaban sin poder ni entrar al sistema).
    const cajas$ = this.auth.puedeVerFinanzas()
      ? this.finanzasService.obtenerResumen().pipe(catchError(() => of(null)))
      : of(null);
    forkJoin({
      pedidos: this.pedidosService.listarTodos(),
      materiales: this.stockService.listarActivos(),
      cajas: cajas$,
    }).subscribe({
      next: ({ pedidos, materiales, cajas }) => {
        this.pedidos = pedidos;
        this.materiales = materiales;
        this.resumenCajas = cajas;
        this.calcular();
        if (!silencioso) this.loading = false;
      },
      error: err => {
        console.error('Error cargando datos del home:', err);
        if (!silencioso) this.loading = false;
      },
    });
  }

  private calcular(): void {
    // Estado actual del laboratorio — nunca se filtra por fecha de creación:
    // un pedido creado hace dos semanas y todavía activo sigue siendo tan
    // "activo" como uno creado hoy. Antes esto se filtraba por un período
    // Hoy/Semana/Mes según fechaCreacion, lo que hacía que pedidos activos
    // reales (y sus alertas de vencido/atrasado) desaparecieran del inicio
    // apenas pasaban unos días desde que se cargaron.
    const activos      = this.pedidos.filter(p => this.esActivo(p.estado));
    const listos       = this.pedidos.filter(p => p.estado === 'LISTO');
    const urgentes     = activos.filter(p => p.prioridad === 'URGENTE');
    const stockBajo    = this.materiales.filter(m => m.bajoStock);
    const stockAgotado = this.materiales.filter(m => m.stockActual === 0);

    const saldoPendiente = this.resumenCajas
      ? Math.max(0, this.resumenCajas.totalDeudaProveedores + this.resumenCajas.totalSueldosPendientes)
      : 0;

    this.kpis = [
      {
        label: 'Pedidos activos',
        value: String(activos.length),
        delta: urgentes.length > 0
          ? { texto: `${urgentes.length} urgente${urgentes.length !== 1 ? 's' : ''}`, clase: 'up' }
          : undefined,
        hue: 'var(--color-info)',
        sparkline: this.generarSparkline(activos.length, 7, 0.15),
        sparkColor: 'var(--color-info)',
        to: '/dashboard/pedidos',
      },
      {
        label: 'Listos p/ entregar',
        value: String(listos.length),
        delta: listos.length > 0
          ? { texto: `${listos.length} retiran hoy`, clase: 'flat' }
          : { texto: 'Sin pendientes', clase: 'flat' },
        hue: 'var(--color-success)',
        sparkline: this.generarSparkline(listos.length, 7, 0.2),
        sparkColor: 'var(--color-success)',
        to: '/dashboard/entregas',
      },
      // Plata (sueldos/deudas) es ADMIN/ADMINISTRATIVO en el backend — para el
      // resto de roles ni se pide el dato (ver `cargar()`), así que tampoco
      // mostramos esta tarjeta: mostrar "$0" sería engañoso, no "sin deuda".
      ...(this.auth.puedeVerFinanzas() ? [{
        label: 'Saldo pendiente',
        value: this.formatMoney(saldoPendiente),
        delta: saldoPendiente > 0
          ? { texto: 'Por cobrar y pagar', clase: 'down' as const }
          : { texto: 'Al día', clase: 'flat' as const },
        hue: 'var(--color-warning)',
        sparkline: this.generarSparkline(saldoPendiente / 100000, 7, 0.18),
        sparkColor: 'var(--color-warning)',
        to: '/dashboard/finanzas',
      }] : []),
      {
        label: 'Stock bajo mínimo',
        value: String(stockBajo.length),
        delta: stockAgotado.length > 0
          ? { texto: `${stockAgotado.length} crítico${stockAgotado.length !== 1 ? 's' : ''}`, clase: 'down' }
          : { texto: 'Sin críticos', clase: 'flat' },
        hue: 'var(--color-danger)',
        sparkline: this.generarSparkline(stockBajo.length, 7, 0.25),
        sparkColor: 'var(--color-danger)',
        to: '/dashboard/stock',
      },
    ];

    this.pedidosRecientes = this.pedidos
      .filter(p => this.esActivo(p.estado) || p.estado === 'LISTO')
      .sort((a, b) => new Date(b.fechaCreacion).getTime() - new Date(a.fechaCreacion).getTime())
      .slice(0, 6);

    this.alertas = this.calcularAlertas(activos, listos, urgentes, stockBajo, stockAgotado);
  }

  private calcularAlertas(
    activos: PedidoResponse[],
    listos: PedidoResponse[],
    urgentes: PedidoResponse[],
    stockBajo: MaterialResponse[],
    stockAgotado: MaterialResponse[],
  ): AlertaItem[] {
    const items: AlertaItem[] = [];
    const hoy = new Date(); hoy.setHours(0, 0, 0, 0);

    // 1. Pedido vencido
    const vencidos = activos.concat(listos)
      .filter(p => fechaLocal(p.fechaEntrega) < hoy)
      .slice(0, 1);
    vencidos.forEach(p => {
      items.push({
        hue: 'var(--color-danger)',
        titulo: `${p.nroPedido} vencido`,
        subtitulo: `${p.odontologoNombre} · era ${this.formatFecha(p.fechaEntrega)}`,
        badge: 'URGENTE',
        to: '/dashboard/pedidos',
      });
    });

    // 1.5. Pedidos atrasados (>= 6 días hábiles sin entregar) — ámbar
    const atrasados = activos.concat(listos).filter(p => this.pedidosService.estaAtrasado(p));
    if (atrasados.length > 0) {
      items.push({
        hue: '#ea580c',  // ámbar/naranja (no rojo de vencido)
        titulo: `${atrasados.length} pedido${atrasados.length !== 1 ? 's' : ''} atrasado${atrasados.length !== 1 ? 's' : ''}`,
        subtitulo: atrasados.slice(0, 2).map(p => `${p.nroPedido} (${p.diasHabilesTranscurridos}d háb.)`).join(' · '),
        to: '/dashboard/pedidos',
      });
    }

    // 2. Stock agotado o crítico
    stockAgotado.slice(0, 1).forEach(m => {
      items.push({
        hue: 'var(--color-danger)',
        titulo: `${m.nombre} agotado`,
        subtitulo: `Stock 0 · mínimo ${m.stockMinimo} ${m.unidadMedida}`,
        badge: 'CRITICO',
        to: '/dashboard/stock',
      });
    });

    stockBajo.filter(m => m.stockActual > 0).slice(0, 1).forEach(m => {
      items.push({
        hue: 'var(--color-warning)',
        titulo: `${m.nombre} bajo mínimo`,
        subtitulo: `Quedan ${m.stockActual} · mínimo ${m.stockMinimo} ${m.unidadMedida}`,
        valor: `${m.stockActual}`,
        to: '/dashboard/stock',
      });
    });

    // 3. Urgentes
    if (urgentes.length > 0) {
      items.push({
        hue: 'var(--color-warning)',
        titulo: `${urgentes.length} pedido${urgentes.length !== 1 ? 's' : ''} urgente${urgentes.length !== 1 ? 's' : ''}`,
        subtitulo: urgentes.slice(0, 2).map(p => p.nroPedido).join(' · '),
        to: '/dashboard/pedidos',
      });
    }

    // 4. Listos
    if (listos.length > 0) {
      items.push({
        hue: 'var(--color-success)',
        titulo: `${listos.length} pedido${listos.length !== 1 ? 's' : ''} para retirar`,
        subtitulo: listos.slice(0, 2).map(p => p.nroPedido).join(' · '),
        to: '/dashboard/entregas',
      });
    }

    // 5. Caja compensación
    if (this.resumenCajas && this.resumenCajas.saldoCompensacion !== 0) {
      items.push({
        hue: 'var(--color-special)',
        titulo: 'Caja compensación',
        subtitulo: 'Debe quedar en $0',
        valor: this.formatMoney(this.resumenCajas.saldoCompensacion),
        to: '/dashboard/finanzas',
      });
    } else if (this.resumenCajas) {
      items.push({
        hue: 'var(--color-neutral)',
        titulo: 'Caja compensación',
        subtitulo: 'Cerrada en cero ✓',
        valor: '$0',
      });
    }

    return items.slice(0, 6);
  }

  // ── Helpers ───────────────────────────────────────────────

  esActivo(estado: EstadoPedido): boolean {
    return estado === 'RECIBIDO' || estado === 'EN_PROCESO' || estado === 'CONTROL';
  }

  chipClass(estado: EstadoPedido): string {
    const m: Record<EstadoPedido, string> = {
      'RECIBIDO':   'c-recibido',
      'EN_PROCESO': 'c-proceso',
      'CONTROL':    'c-control',
      'LISTO':      'c-listo',
      'ENTREGADO':  'c-entregado',
      'CANCELADO':  'c-cancelado',
    };
    return m[estado] ?? 'c-recibido';
  }

  labelEstado(estado: EstadoPedido): string {
    const m: Record<EstadoPedido, string> = {
      'RECIBIDO':   'Recibido',
      'EN_PROCESO': 'En proceso',
      'CONTROL':    'Control',
      'LISTO':      'Listo',
      'ENTREGADO':  'Entregado',
      'CANCELADO':  'Cancelado',
    };
    return m[estado] ?? estado;
  }

  diasParaEntrega(fecha: string): { texto: string; vencido: boolean } {
    const hoy = new Date(); hoy.setHours(0, 0, 0, 0);
    const f = fechaLocal(fecha); f.setHours(0, 0, 0, 0);
    const dias = Math.round((f.getTime() - hoy.getTime()) / 86_400_000);
    if (dias < 0)  return { texto: 'Vencido',          vencido: true  };
    if (dias === 0) return { texto: 'Hoy',              vencido: false };
    if (dias === 1) return { texto: '1 día',            vencido: false };
    return { texto: `${dias} días`, vencido: false };
  }

  formatMoney(n: number | null): string {
    if (n == null) return '$0';
    return new Intl.NumberFormat('es-AR', {
      style: 'currency', currency: 'ARS', maximumFractionDigits: 0
    }).format(n);
  }

  formatFecha(iso: string): string {
    return new Date(iso).toLocaleDateString('es-AR', { day: '2-digit', month: '2-digit' });
  }

  formatearFechaLarga(d: Date): string {
    return d.toLocaleDateString('es-AR', {
      weekday: 'long', day: 'numeric', month: 'long', year: 'numeric'
    });
  }

  /** Genera sparkline fake con tendencia hacia el valor actual. */
  private generarSparkline(valorActual: number, puntos: number, variacion: number): number[] {
    const base = Math.max(1, valorActual);
    const r: number[] = [];
    let actual = base * (1 - variacion);
    for (let i = 0; i < puntos - 1; i++) {
      actual += base * (Math.random() - 0.45) * variacion;
      r.push(Math.max(0, actual));
    }
    r.push(base);
    return r;
  }

  /** Convierte un array de valores en puntos para SVG polyline. */
  sparklinePath(valores: number[], w = 64, h = 24): string {
    if (valores.length === 0) return '';
    const max = Math.max(...valores, 1);
    const min = Math.min(...valores, 0);
    const range = max - min || 1;
    const step = w / (valores.length - 1);
    return valores.map((v, i) => {
      const x = i * step;
      const y = h - ((v - min) / range) * h;
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    }).join(' ');
  }

  goTo(route?: string): void {
    if (route) this.router.navigate([route]);
  }

  abrirDetalle(pedidoId: number): void {
    this.detalleAbiertoId = pedidoId;
  }

  cerrarDetalle(): void {
    this.detalleAbiertoId = null;
  }
}
