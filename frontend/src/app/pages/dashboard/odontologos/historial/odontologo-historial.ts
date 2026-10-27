import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
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
import { OdontologoArchivosComponent } from './odontologo-archivos';
import { SectionTabsComponent, SeccionTab } from '../../../../shared/section-tabs';
import { iniciarPolling } from '../../../../shared/poll.util';

type Tab = 'resumen' | 'pedidos' | 'finanzas' | 'escaneos' | 'documentos';

/**
 * Vista 360° del odontólogo: todo lo suyo en una sola pantalla, repartido en pestañas que quedan siempre a la vista.
 *   - Resumen y Pedidos: sus datos y el listado de sus pedidos
 *   - Finanzas: cuenta corriente, comprobantes y pagos (solo ADMIN y ADMINISTRATIVO)
 *   - Escaneos 3D y Documentos: los archivos de todos sus pedidos
 *
 * El frontend junta varias consultas (forkJoin); si una falla, el resto sigue y lo que ya se veía no se pisa.
 */
@Component({
  selector: 'app-odontologo-historial',
  imports: [RouterLink, SectionTabsComponent, PagoCuentaCorrienteModalComponent, PedidoDetalleModalComponent, OdontologoArchivosComponent],
  templateUrl: './odontologo-historial.html',
  styleUrl: './odontologo-historial.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OdontologoHistorialComponent implements OnInit {

  private readonly route = inject(ActivatedRoute);
  private readonly odontologosService = inject(OdontologosService);
  private readonly pedidosService = inject(PedidosService);
  private readonly finanzasService = inject(FinanzasService);
  private readonly notif = inject(NotificationService);
  private readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * Técnicos no ven deuda/facturación: solo ADMIN y ADMINISTRATIVO manejan plata. Es un método y no un computed porque
   * el rol sale de la sesión, que no es una señal.
   */
  puedeVerFinanzas(): boolean {
    return this.auth.puedeVerFinanzas();
  }

  readonly loading = signal(true);
  readonly errorCarga = signal('');

  readonly odontologo = signal<OdontologoResponse | null>(null);
  readonly pedidos = signal<PedidoResponse[]>([]);
  readonly saldoDeuda = signal(0);
  readonly comprobantes = signal<ComprobanteResponse[]>([]);
  readonly historialPagos = signal<PagoCuentaCorrienteResponse[]>([]);

  readonly tabActiva = signal<Tab>('resumen');
  readonly showModalPago = signal(false);
  readonly detalleAbiertoId = signal<number | null>(null);

  /** Las pestañas llevan la cantidad de pedidos; Finanzas solo aparece para quien puede ver plata. */
  tabs(): SeccionTab[] {
    return [
      { id: 'resumen', etiqueta: 'Resumen' },
      { id: 'pedidos', etiqueta: 'Pedidos', cantidad: this.pedidos().length },
      ...(this.puedeVerFinanzas() ? [{ id: 'finanzas', etiqueta: 'Finanzas' }] : []),
      { id: 'escaneos', etiqueta: 'Escaneos 3D' },
      { id: 'documentos', etiqueta: 'Documentos' },
    ];
  }

  ngOnInit(): void {
    const id = Number(this.route.snapshot.paramMap.get('id'));
    if (!id) {
      this.errorCarga.set('ID de odontólogo no válido');
      this.loading.set(false);
      return;
    }
    this.cargar(id);
    iniciarPolling(() => this.cargar(id, true), this.destroyRef);
  }

  private cargar(id: number, silencioso = false): void {
    if (silencioso && this.showModalPago()) return; // no pisar mientras hay un pago en curso
    if (!silencioso) this.loading.set(true);
    // Si una consulta falla NO se reemplaza lo que ya se ve por "0" o una lista vacía: eso mostraría "no debe nada"
    // cuando en realidad no se pudo consultar. Se conserva lo anterior y, si el usuario recién entró, se le avisa.
    let fallo = false;
    const sinDatos = () => { fallo = true; return of(null); };
    forkJoin({
      odontologo: this.odontologosService.buscarPorId(id).pipe(
        catchError(err => {
          if (!silencioso) this.notif.errorHttp(err, 'No se pudo cargar el odontólogo');
          return of(null);
        })
      ),
      pedidos: this.pedidosService.listarTodos().pipe(catchError(sinDatos)),
      saldo: this.puedeVerFinanzas()
        ? this.finanzasService.saldoPorOdontologo(id).pipe(catchError(sinDatos))
        : of(0),
      comprobantes: this.puedeVerFinanzas()
        ? this.finanzasService.comprobantesPorOdontologo(id).pipe(catchError(sinDatos))
        : of([] as ComprobanteResponse[]),
      historialPagos: this.puedeVerFinanzas()
        ? this.finanzasService.historialPagosOdontologo(id).pipe(catchError(sinDatos))
        : of([] as PagoCuentaCorrienteResponse[]),
    }).subscribe(({ odontologo, pedidos, saldo, comprobantes, historialPagos }) => {
      this.odontologo.set(odontologo);
      if (pedidos !== null) {
        this.pedidos.set(pedidos.filter(p => p.odontologoId === id)
                                .sort((a, b) => new Date(b.fechaCreacion).getTime() - new Date(a.fechaCreacion).getTime()));
      }
      if (saldo !== null) this.saldoDeuda.set(saldo);
      if (comprobantes !== null) this.comprobantes.set(comprobantes);
      if (historialPagos !== null) this.historialPagos.set(historialPagos);
      if (!silencioso) this.loading.set(false);
      if (fallo && !silencioso) {
        this.notif.alerta('No se pudo cargar parte de la cuenta corriente: los montos que ves pueden estar desactualizados.', 'Datos incompletos');
      }
    });
  }

  /** La barra de pestañas avisa con texto: acá se vuelve una pestaña conocida (y que la persona pueda ver). */
  cambiarTab(id: string): void {
    if (this.tabs().some(t => t.id === id)) this.tabActiva.set(id as Tab);
  }

  abrirModalPago(): void {
    this.showModalPago.set(true);
  }

  cerrarModalPago(): void {
    this.showModalPago.set(false);
  }

  onPagoRegistrado(): void {
    this.showModalPago.set(false);
    const o = this.odontologo();
    if (!o) return;
    // Un solo mensaje para las tres: si fallan juntas (sin conexión) el aviso se muestra una vez (preventDuplicates).
    const avisar = () => this.notif.alerta('El pago se registró, pero no se pudo actualizar la cuenta corriente. Recargá la página para ver los montos al día.', 'Datos desactualizados');
    this.finanzasService.saldoPorOdontologo(o.id).subscribe({
      next: saldo => this.saldoDeuda.set(saldo),
      error: avisar,
    });
    this.finanzasService.comprobantesPorOdontologo(o.id).subscribe({
      next: comps => this.comprobantes.set(comps),
      error: avisar,
    });
    this.finanzasService.historialPagosOdontologo(o.id).subscribe({
      next: pagos => this.historialPagos.set(pagos),
      error: avisar,
    });
  }

  abrirDetallePedido(pedidoId: number): void {
    this.detalleAbiertoId.set(pedidoId);
  }

  cerrarDetallePedido(): void {
    this.detalleAbiertoId.set(null);
  }

  // ─── Stats derivadas ────────────────────────────────────────────────

  readonly pedidosActivos = computed(() => this.pedidos().filter(p => p.estado !== 'ENTREGADO' && p.estado !== 'CANCELADO'));
  readonly pedidosEntregados = computed(() => this.pedidos().filter(p => p.estado === 'ENTREGADO'));
  readonly subtotalActivos = computed(() => this.pedidosActivos().reduce((sum, p) => sum + (p.precioAcordado ?? 0), 0));
  readonly totalFacturado = computed(() => this.pedidos()
    .filter(p => p.estado === 'ENTREGADO' || p.estado === 'LISTO')
    .reduce((sum, p) => sum + (p.precioAcordado ?? 0), 0));
  readonly fechaUltimoPedido = computed(() => this.pedidos()[0]?.fechaCreacion ?? null);

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
