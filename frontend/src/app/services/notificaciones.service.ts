import { Injectable, signal, computed, inject } from '@angular/core';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { PedidosService } from './pedidos.service';
import { StockService } from './stock.service';
import { SueldosService } from './sueldos.service';
import { SolicitudesService } from './solicitudes.service';
import { AuthService } from './auth';

export type TipoNoti = 'danger' | 'warning' | 'success' | 'info' | 'neutral';
export type IconoNoti = 'alerta' | 'caja' | 'efectivo' | 'entrega' | 'usuario' | 'bot';

export interface Notificacion {
  id: string;
  tipo: TipoNoti;
  icono: IconoNoti;
  titulo: string;
  detalle: string;
  ruta: string;
  queryParams?: Record<string, string>;
}

/**
 * Agregador de notificaciones del panel (Modelo "acciones pendientes").
 *
 * No son mensajes que se acumulan: son un REFLEJO del estado actual del
 * laboratorio. Se derivan en vivo de los modulos que ya existen y desaparecen
 * solas cuando se resuelve la causa (se entrega el atrasado, se repone el
 * stock, se confirma el efectivo). Por eso no hace falta una tabla/pagina de
 * notificaciones: alcanza con recomputar contra los endpoints existentes.
 *
 * Fuentes:
 *   - pedidos atrasados          -> GET /api/pedidos/atrasados
 *   - entregas pendientes (LISTO)-> GET /api/pedidos/estado/LISTO
 *   - materiales con stock bajo  -> GET /api/stock/bajo-stock
 *   - efectivo por confirmar     -> GET /api/finanzas/sueldos/pendientes-efectivo
 *   - transferencias a revisar   -> GET /api/finanzas/sueldos/pendientes-revision
 *   - solicitudes por mail       -> GET /api/pedidos/solicitudes/cantidad
 *
 * Un fallo de una fuente no tumba al resto (catchError -> []).
 */
@Injectable({ providedIn: 'root' })
export class NotificacionesService {

  private readonly pedidos  = inject(PedidosService);
  private readonly stock    = inject(StockService);
  private readonly sueldos  = inject(SueldosService);
  private readonly solicitudes = inject(SolicitudesService);
  private readonly auth     = inject(AuthService);

  private readonly _items     = signal<Notificacion[]>([]);
  private readonly _ocultas   = signal<Set<string>>(new Set());
  private readonly _cargando  = signal(false);

  /** Notis visibles = derivadas menos las que el usuario oculto en la sesion. */
  readonly items    = computed(() => this._items().filter(n => !this._ocultas().has(n.id)));
  readonly count    = computed(() => this.items().length);
  readonly cargando = this._cargando.asReadonly();

  constructor() {
    this.refrescar();
  }

  /** Recalcula las notificaciones contra el estado actual de cada modulo. */
  refrescar(): void {
    this._cargando.set(true);
    // "efectivo pendiente" es un endpoint de ADMIN/ADMINISTRATIVO en el backend. Si lo
    // pedimos igual para otros roles, el 403 dispara el interceptor global (redirige a
    // /sin-permisos) ANTES de que este catchError llegue a correr — y como este
    // servicio se instancia apenas se entra al dashboard, bloqueaba la app entera
    // para roles que no ven finanzas.
    const efectivo$ = this.auth.puedeVerFinanzas()
      ? this.sueldos.pendientesEfectivo().pipe(catchError(() => of([])))
      : of([]);
    const revision$ = this.auth.puedeVerFinanzas()
      ? this.sueldos.pendientesRevision().pipe(catchError(() => of([])))
      : of([]);
    const nuevas$ = this.solicitudes.cantidadNuevas().pipe(catchError(() => of(0)));
    forkJoin({
      atrasados: this.pedidos.listarAtrasados().pipe(catchError(() => of([]))),
      listos:    this.pedidos.listarPorEstado('LISTO').pipe(catchError(() => of([]))),
      stockBajo: this.stock.listarBajoStock().pipe(catchError(() => of([]))),
      efectivo:  efectivo$,
      revision:  revision$,
      nuevas:    nuevas$,
    }).subscribe(({ atrasados, listos, stockBajo, efectivo, revision, nuevas }) => {
      const notis: Notificacion[] = [];

      if (atrasados.length) {
        const n = atrasados.length;
        const primero = atrasados[0].nroPedido;
        notis.push({
          id: 'pedidos-atrasados',
          tipo: 'danger',
          icono: 'alerta',
          titulo: n === 1 ? '1 pedido atrasado' : `${n} pedidos atrasados`,
          detalle: n === 1 ? `${primero} pasó su fecha de entrega` : `${primero} y ${n - 1} más pasaron su fecha`,
          ruta: '/dashboard/pedidos',
        });
      }

      if (stockBajo.length) {
        const n = stockBajo.length;
        const nombres = stockBajo.slice(0, 2).map(m => m.nombre).join(' · ');
        notis.push({
          id: 'stock-bajo',
          tipo: 'warning',
          icono: 'caja',
          titulo: n === 1 ? '1 material con stock bajo' : `${n} materiales con stock bajo`,
          detalle: n > 2 ? `${nombres} y más` : nombres,
          ruta: '/dashboard/stock',
        });
      }

      if (efectivo.length) {
        const n = efectivo.length;
        const r = efectivo[0];
        const monto = r.monto != null ? ` ($${r.monto.toLocaleString('es-AR')})` : '';
        notis.push({
          id: 'efectivo-pendiente',
          tipo: 'success',
          icono: 'efectivo',
          titulo: n === 1 ? 'Efectivo pendiente de confirmar' : `${n} efectivos pendientes de confirmar`,
          detalle: r.receptorNombre ? `Declarado para ${r.receptorNombre}${monto} por el bot` : `Declarado${monto} por el bot`,
          ruta: '/dashboard/finanzas',
          queryParams: { seccion: 'cajas' },
        });
      }

      if (nuevas > 0) {
        notis.push({
          id: 'solicitudes-nuevas',
          tipo: 'info',
          icono: 'alerta',
          titulo: nuevas === 1 ? '1 solicitud por mail para revisar' : `${nuevas} solicitudes por mail para revisar`,
          detalle: 'Un odontólogo escribió a la casilla del laboratorio: revisala y creá el pedido',
          ruta: '/dashboard/solicitudes',
        });
      }

      if (revision.length) {
        const n = revision.length;
        const r = revision[0];
        const monto = r.monto != null ? ` ($${r.monto.toLocaleString('es-AR')})` : '';
        notis.push({
          id: 'transferencias-revision',
          tipo: 'warning',
          icono: 'alerta',
          titulo: n === 1 ? '1 transferencia para revisar' : `${n} transferencias para revisar`,
          detalle: r.receptorNombre ? `Para ${r.receptorNombre}${monto}: el sistema no la dio por buena` : `El sistema no la dio por buena${monto}`,
          ruta: '/dashboard/finanzas',
          queryParams: { seccion: 'cajas' },
        });
      }

      if (listos.length) {
        const n = listos.length;
        notis.push({
          id: 'entregas-pendientes',
          tipo: 'info',
          icono: 'entrega',
          titulo: n === 1 ? '1 entrega pendiente' : `${n} entregas pendientes`,
          detalle: 'Trabajos listos esperando retiro',
          ruta: '/dashboard/entregas',
        });
      }

      this._items.set(notis);
      this._cargando.set(false);
    });
  }

  /** Oculta todas por la sesion. Reaparecen al refrescar si la causa persiste. */
  marcarTodasLeidas(): void {
    this._ocultas.update(set => {
      const next = new Set(set);
      this._items().forEach(n => next.add(n.id));
      return next;
    });
  }

  /** Oculta una por la sesion. */
  marcarLeida(id: string): void {
    this._ocultas.update(set => new Set(set).add(id));
  }
}
