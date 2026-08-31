import { Component, OnInit, inject, DestroyRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';

import { OdontologosService, OdontologoResponse } from '../../../../services/odontologos.service';
import { PedidosService, PedidoResponse } from '../../../../services/pedidos.service';
import { FinanzasService, ComprobanteResponse, PagoCuentaCorrienteResponse } from '../../../../services/finanzas.service';
import { NotificationService } from '../../../../services/notification.service';
import { AuthService } from '../../../../services/auth';
import { PagoCuentaCorrienteModalComponent } from '../pago-cuenta-corriente-modal/pago-cuenta-corriente-modal.component';
import { PedidoDetalleModalComponent } from '../../pedidos/pedido-detalle-modal/pedido-detalle-modal.component';
import { iniciarPolling } from '../../../../shared/poll.util';

type Tab = 'resumen' | 'pedidos' | 'finanzas' | 'escaneres' | 'documentos';

/**
 * Vista 360° del odontólogo — agrega info de varios MS en una sola pantalla:
 *   - ms-pedidos: datos básicos y listado de pedidos
 *   - ms-finanzas: cuenta corriente, comprobantes pendientes y deuda
 *   - (futuro) ms-mail: escáneres 3D y documentos asociados
 *
 * Pattern: el frontend hace un forkJoin de los services y arma la vista.
 * Cada MS responde independiente; si uno falla, el resto sigue (catchError).
 */
@Component({
  selector: 'app-odontologo-historial',
  standalone: true,
  imports: [CommonModule, RouterLink, PagoCuentaCorrienteModalComponent, PedidoDetalleModalComponent],
  templateUrl: './odontologo-historial.html',
  styleUrls: ['./odontologo-historial.css'],
})
export class OdontologoHistorialComponent implements OnInit {

  private route = inject(ActivatedRoute);
  private odontologosService = inject(OdontologosService);
  private pedidosService = inject(PedidosService);
  private finanzasService = inject(FinanzasService);
  private notif = inject(NotificationService);
  private auth = inject(AuthService);
  private destroyRef = inject(DestroyRef);

  /** Técnicos no ven deuda/facturación — solo ADMIN y ADMINISTRATIVO manejan plata. */
  get puedeVerFinanzas(): boolean {
    return this.auth.puedeVerFinanzas();
  }

  loading = true;
  errorCarga = '';

  odontologo: OdontologoResponse | null = null;
  pedidos: PedidoResponse[] = [];
  saldoDeuda = 0;
  comprobantes: ComprobanteResponse[] = [];
  historialPagos: PagoCuentaCorrienteResponse[] = [];

  tabActiva: Tab = 'resumen';

  // Modal registrar pago
  showModalPago = false;

  // Modal detalle de pedido
  detalleAbiertoId: number | null = null;

  // ─── Tabs disponibles (los últimos 2 son placeholders por ahora) ────
  get tabs(): { id: Tab; label: string; count?: () => number }[] {
    return [
      { id: 'resumen',    label: 'Resumen' },
      { id: 'pedidos',    label: 'Pedidos', count: () => this.pedidos.length },
      ...(this.puedeVerFinanzas ? [{ id: 'finanzas' as Tab, label: 'Finanzas' }] : []),
      { id: 'escaneres',  label: 'Escáneres 3D' },
      { id: 'documentos', label: 'Documentos' },
    ];
  }

  ngOnInit(): void {
    const id = Number(this.route.snapshot.paramMap.get('id'));
    if (!id) {
      this.errorCarga = 'ID de odontólogo no válido';
      this.loading = false;
      return;
    }
    this.cargar(id);
    iniciarPolling(() => this.cargar(id, true), this.destroyRef);
  }

  private cargar(id: number, silencioso = false): void {
    if (silencioso && this.showModalPago) return; // no pisar mientras hay un pago en curso
    if (!silencioso) this.loading = true;
    forkJoin({
      odontologo: this.odontologosService.buscarPorId(id).pipe(
        catchError(err => {
          if (!silencioso) this.notif.errorHttp(err, 'No se pudo cargar el odontólogo');
          return of(null);
        })
      ),
      pedidos: this.pedidosService.listarTodos().pipe(
        catchError(() => of([] as PedidoResponse[]))
      ),
      saldo: this.puedeVerFinanzas
        ? this.finanzasService.saldoPorOdontologo(id).pipe(catchError(() => of(0)))
        : of(0),
      comprobantes: this.puedeVerFinanzas
        ? this.finanzasService.comprobantesPorOdontologo(id).pipe(catchError(() => of([] as ComprobanteResponse[])))
        : of([] as ComprobanteResponse[]),
      historialPagos: this.puedeVerFinanzas
        ? this.finanzasService.historialPagosOdontologo(id).pipe(catchError(() => of([] as PagoCuentaCorrienteResponse[])))
        : of([] as PagoCuentaCorrienteResponse[]),
    }).subscribe(({ odontologo, pedidos, saldo, comprobantes, historialPagos }) => {
      this.odontologo = odontologo;
      this.pedidos = pedidos.filter(p => p.odontologoId === id)
                            .sort((a, b) => new Date(b.fechaCreacion).getTime() - new Date(a.fechaCreacion).getTime());
      this.saldoDeuda = saldo;
      this.comprobantes = comprobantes;
      this.historialPagos = historialPagos;
      if (!silencioso) this.loading = false;
    });
  }

  cambiarTab(t: Tab): void {
    this.tabActiva = t;
  }

  abrirModalPago(): void {
    this.showModalPago = true;
  }

  cerrarModalPago(): void {
    this.showModalPago = false;
  }

  onPagoRegistrado(): void {
    this.showModalPago = false;
    if (!this.odontologo) return;
    const id = this.odontologo.id;
    this.finanzasService.saldoPorOdontologo(id).subscribe({
      next: saldo => this.saldoDeuda = saldo,
      error: () => {},
    });
    this.finanzasService.comprobantesPorOdontologo(id).subscribe({
      next: comps => this.comprobantes = comps,
      error: () => {},
    });
    this.finanzasService.historialPagosOdontologo(id).subscribe({
      next: pagos => this.historialPagos = pagos,
      error: () => {},
    });
  }

  abrirDetallePedido(pedidoId: number): void {
    this.detalleAbiertoId = pedidoId;
  }

  cerrarDetallePedido(): void {
    this.detalleAbiertoId = null;
  }

  // ─── Stats derivadas ────────────────────────────────────────────────

  get pedidosActivos(): PedidoResponse[] {
    return this.pedidos.filter(p => p.estado !== 'ENTREGADO' && p.estado !== 'CANCELADO');
  }

  get pedidosEntregados(): PedidoResponse[] {
    return this.pedidos.filter(p => p.estado === 'ENTREGADO');
  }

  get subtotalActivos(): number {
    return this.pedidosActivos.reduce((sum, p) => sum + (p.precioAcordado ?? 0), 0);
  }

  get totalFacturado(): number {
    return this.pedidos
      .filter(p => p.estado === 'ENTREGADO' || p.estado === 'LISTO')
      .reduce((sum, p) => sum + (p.precioAcordado ?? 0), 0);
  }

  get fechaUltimoPedido(): string | null {
    if (this.pedidos.length === 0) return null;
    return this.pedidos[0].fechaCreacion;
  }

  // ─── Helpers de vista ───────────────────────────────────────────────

  iniciales(nombre: string): string {
    return nombre.replace(/^(Dr\.|Dra\.)\s*/i, '')
      .split(' ').filter(Boolean).slice(0, 2)
      .map(p => p[0]?.toUpperCase() ?? '').join('');
  }

  formatMoney(n: number): string {
    return new Intl.NumberFormat('es-AR', {
      style: 'currency', currency: 'ARS', maximumFractionDigits: 0
    }).format(n);
  }

  formatFecha(iso: string | null): string {
    if (!iso) return '—';
    return new Date(iso).toLocaleDateString('es-AR',
      { day: '2-digit', month: '2-digit', year: 'numeric' });
  }

  colorEstado(e: string): string {
    const m: Record<string, string> = {
      RECIBIDO: 'blue', EN_PROCESO: 'amber',
      CONTROL: 'purple', LISTO: 'green',
      ENTREGADO: 'cyan', CANCELADO: 'rose',
    };
    return m[e] ?? 'muted';
  }

  labelEstado(e: string): string {
    const m: Record<string, string> = {
      RECIBIDO: 'Recibido', EN_PROCESO: 'En proceso',
      CONTROL: 'Control', LISTO: 'Listo',
      ENTREGADO: 'Entregado', CANCELADO: 'Cancelado',
    };
    return m[e] ?? e;
  }
}
