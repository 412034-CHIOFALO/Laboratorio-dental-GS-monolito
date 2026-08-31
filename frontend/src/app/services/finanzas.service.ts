import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { delay } from 'rxjs/operators';
import { environment } from '../../environments/environment';
import { MOCK_RESUMEN_CAJAS, MOCK_MOVIMIENTOS_CAJA, clonar } from './mock-data';

export type TipoCaja = 'FISICA' | 'BANCARIA' | 'COMPENSACION';
export type TipoMovimientoCaja = 'INGRESO' | 'EGRESO';

export interface ResumenCajasResponse {
  saldoFisica: number;
  saldoBancaria: number;
  saldoCompensacion: number;
  totalDeudaProveedores: number;
  totalSueldosPendientes: number;
  alertas: string[];
  descuadresCompensacion: DescuadreCompensacion[];
}

/** Triangulado incompleto en Caja Compensación (le falta la mitad del par ingreso/egreso). */
export interface DescuadreCompensacion {
  referencia: string | null;
  monto: number;
  concepto: string;
  fecha: string;
}

export interface CajaMovimientoResponse {
  id: number;
  tipo: TipoMovimientoCaja;
  tipoCaja: TipoCaja;
  concepto: string;
  monto: number;
  referencia: string | null;
  fechaMovimiento: string;
  creadoPor: string | null;
}

/** Categorías rápidas para clasificar un movimiento manual. */
export type CategoriaMovimiento =
  | 'COBRO_ODONTOLOGO' | 'EFECTIVO_CADETE' | 'PAGO_PROVEEDOR' | 'PAGO_SUELDO'
  | 'GASTO_MENOR' | 'AJUSTE' | 'OTRO';

export interface CajaMovimientoRequest {
  tipo: TipoMovimientoCaja;
  tipoCaja: TipoCaja;
  concepto: string;
  monto: number;
  categoria: CategoriaMovimiento;
  referencia?: string | null;
  fechaMovimiento?: string | null;   // default: hoy
  creadoPor?: string | null;
}

export type SeveridadDeuda = 'AL_DIA' | 'BAJA' | 'MEDIA' | 'ALTA' | 'CRITICA';

export interface CuentaCorrienteOdontologoResponse {
  odontologoId: number;
  odontologoNombre: string;
  totalDeuda: number;
  comprobantesPendientes: number;
  fechaMasAntigua: string | null;
  diasSinPagar: number;
  severidad: SeveridadDeuda;
}

export type EstadoPago = 'PENDIENTE' | 'PARCIAL' | 'COBRADO' | 'VENCIDO';
export type MedioPago = 'EFECTIVO' | 'TRANSFERENCIA';

export interface ReporteMensualResponse {
  id: number;
  anio: number;
  mes: number;
  periodo: string;
  nombreArchivo: string;
  generadoEn: string;
  automatico: boolean;
}

export interface ComprobanteResponse {
  id: number;
  nroComprobante: string;
  pedidoId: number | null;
  nroPedido: string;
  odontologoId: number;
  odontologoNombre: string;
  trabajo: string;
  monto: number;
  montoPagado: number;
  saldoPendiente: number;
  estadoPago: EstadoPago;
  fechaEmision: string;
  fechaVencimiento: string | null;
  fechaCobro: string | null;
  observaciones: string | null;
}

export interface PagoCuentaCorrienteRequest {
  monto: number;
  medio: MedioPago;
  fecha?: string | null;
  nota?: string | null;
}

export interface PagoCuentaCorrienteResponse {
  id: number;
  odontologoId: number;
  odontologoNombre: string;
  monto: number;
  montoImputado: number;
  medio: MedioPago;
  fecha: string;
  nota: string | null;
  comprobantesAfectados: number;
  saldoRestante: number;
  mensaje: string;
}

@Injectable({ providedIn: 'root' })
export class FinanzasService {

  private readonly base = `${environment.gatewayUrl}/api/finanzas`;

  /** Store mutable en memoria para mocks (permite agregar movimientos a mano). */
  private movStore: CajaMovimientoResponse[] = clonar(MOCK_MOVIMIENTOS_CAJA);
  private nextMovId = 9000;

  constructor(private http: HttpClient) {}

  obtenerResumen(): Observable<ResumenCajasResponse> {
    if (environment.useMocks) {
      return of(clonar(MOCK_RESUMEN_CAJAS)).pipe(delay(200));
    }
    return this.http.get<ResumenCajasResponse>(`${this.base}/cajas/resumen`);
  }

  movimientosPorCaja(tipoCaja: TipoCaja): Observable<CajaMovimientoResponse[]> {
    if (environment.useMocks) {
      const filtrados = this.movStore
        .filter(m => m.tipoCaja === tipoCaja)
        .sort((a, b) => new Date(b.fechaMovimiento).getTime() - new Date(a.fechaMovimiento).getTime());
      return of(clonar(filtrados)).pipe(delay(180));
    }
    return this.http.get<CajaMovimientoResponse[]>(`${this.base}/cajas/${tipoCaja}/movimientos`);
  }

  /** Carga manual de un movimiento en una caja. */
  registrarMovimientoCaja(req: CajaMovimientoRequest): Observable<CajaMovimientoResponse> {
    if (environment.useMocks) {
      const nuevo: CajaMovimientoResponse = {
        id: this.nextMovId++,
        tipo: req.tipo,
        tipoCaja: req.tipoCaja,
        concepto: req.concepto,
        monto: req.monto,
        referencia: req.referencia ?? null,
        fechaMovimiento: req.fechaMovimiento ?? new Date().toISOString().slice(0, 10),
        creadoPor: req.creadoPor ?? 'admin',
      };
      this.movStore.unshift(nuevo);
      return of(clonar(nuevo)).pipe(delay(200));
    }
    return this.http.post<CajaMovimientoResponse>(`${this.base}/cajas/movimiento`, req);
  }

  /**
   * Ranking de odontólogos con deuda pendiente. Ordenado de mayor deuda a menor.
   * Mocks: se generan desde el listado de pedidos sin pagar.
   */
  /**
   * @param todos si es true, incluye también a los odontólogos que ya saldaron
   *   toda su deuda (quedan con totalDeuda = 0) — antes esta pantalla siempre
   *   traía solo deudores, así que la pestaña "Todos" nunca mostraba nada
   *   distinto de "Solo morosos".
   */
  rankingMorosos(todos = false): Observable<CuentaCorrienteOdontologoResponse[]> {
    if (environment.useMocks) {
      const base = this.generarRankingMock();
      return of(todos ? [...base, this.alDiaMock()] : base).pipe(delay(220));
    }
    let params = new HttpParams();
    if (todos) params = params.set('todos', 'true');
    return this.http.get<CuentaCorrienteOdontologoResponse[]>(`${this.base}/cuentas-corrientes`, { params });
  }

  /** Un odontólogo "al día" para que la demo muestre la diferencia entre "Todos" y "Solo morosos". */
  private alDiaMock(): CuentaCorrienteOdontologoResponse {
    return { odontologoId: 9, odontologoNombre: 'Dra. Camila Ferrero', totalDeuda: 0, comprobantesPendientes: 0, fechaMasAntigua: null, diasSinPagar: 0, severidad: 'AL_DIA' };
  }

  /** Genera datos demo para el ranking (solo en modo mocks). */
  private generarRankingMock(): CuentaCorrienteOdontologoResponse[] {
    const hoy = new Date();
    const candidatos: CuentaCorrienteOdontologoResponse[] = [
      { odontologoId: 1, odontologoNombre: 'Dr. Martín García',  totalDeuda: 287500, comprobantesPendientes: 5, fechaMasAntigua: this.diasAtras(78), diasSinPagar: 78, severidad: 'ALTA' },
      { odontologoId: 2, odontologoNombre: 'Dra. Laura Sánchez', totalDeuda: 195000, comprobantesPendientes: 3, fechaMasAntigua: this.diasAtras(42), diasSinPagar: 42, severidad: 'MEDIA' },
      { odontologoId: 3, odontologoNombre: 'Dr. José Pérez',     totalDeuda: 580000, comprobantesPendientes: 9, fechaMasAntigua: this.diasAtras(112), diasSinPagar: 112, severidad: 'CRITICA' },
      { odontologoId: 4, odontologoNombre: 'Dra. Romina Castro', totalDeuda: 45000,  comprobantesPendientes: 1, fechaMasAntigua: this.diasAtras(8),  diasSinPagar: 8,  severidad: 'BAJA' },
      { odontologoId: 5, odontologoNombre: 'Dr. Pablo Gómez',    totalDeuda: 132000, comprobantesPendientes: 4, fechaMasAntigua: this.diasAtras(35), diasSinPagar: 35, severidad: 'MEDIA' },
      { odontologoId: 6, odontologoNombre: 'Dra. Sofía Romero',  totalDeuda: 78500,  comprobantesPendientes: 2, fechaMasAntigua: this.diasAtras(15), diasSinPagar: 15, severidad: 'MEDIA' },
      { odontologoId: 7, odontologoNombre: 'Dr. Lucas Torres',   totalDeuda: 28000,  comprobantesPendientes: 1, fechaMasAntigua: this.diasAtras(4),  diasSinPagar: 4,  severidad: 'BAJA' },
      { odontologoId: 8, odontologoNombre: 'Dra. Valeria Núñez', totalDeuda: 412000, comprobantesPendientes: 7, fechaMasAntigua: this.diasAtras(95), diasSinPagar: 95, severidad: 'CRITICA' },
    ];
    return candidatos.sort((a, b) => b.totalDeuda - a.totalDeuda);
  }

  private diasAtras(n: number): string {
    const d = new Date();
    d.setDate(d.getDate() - n);
    return d.toISOString().slice(0, 10);
  }

  /** Saldo pendiente (deuda) de un odontólogo puntual. */
  saldoPorOdontologo(odontologoId: number): Observable<number> {
    if (environment.useMocks) {
      const c = this.generarRankingMock().find(x => x.odontologoId === odontologoId);
      return of(c?.totalDeuda ?? 0).pipe(delay(100));
    }
    return this.http.get<number>(`${this.base}/saldo/odontologo/${odontologoId}`);
  }

  movimientosPorPeriodo(desde: string, hasta: string): Observable<CajaMovimientoResponse[]> {
    if (environment.useMocks) {
      const desdeMs = new Date(desde).getTime();
      const hastaMs = new Date(hasta).getTime();
      const filtrados = MOCK_MOVIMIENTOS_CAJA
        .filter(m => {
          const ms = new Date(m.fechaMovimiento).getTime();
          return ms >= desdeMs && ms <= hastaMs;
        })
        .sort((a, b) => new Date(b.fechaMovimiento).getTime() - new Date(a.fechaMovimiento).getTime());
      return of(clonar(filtrados)).pipe(delay(200));
    }
    const params = new HttpParams().set('desde', desde).set('hasta', hasta);
    return this.http.get<CajaMovimientoResponse[]>(`${this.base}/cajas/movimientos`, { params });
  }

  // ── Reportes en PDF (cierre diario / resumen mensual) ─────────────

  /** PDF del cierre diario de caja. Si no se pasa fecha, el backend usa hoy. */
  descargarCierreDiarioPdf(fecha?: string): Observable<Blob> {
    let params = new HttpParams();
    if (fecha) params = params.set('fecha', fecha);
    return this.http.get(`${this.base}/cajas/reporte/cierre-diario`, { params, responseType: 'blob' });
  }

  /** PDF del resumen mensual de caja para el año/mes indicados. */
  descargarResumenMensualPdf(anio: number, mes: number): Observable<Blob> {
    const params = new HttpParams().set('anio', anio).set('mes', mes);
    return this.http.get(`${this.base}/cajas/reporte/mensual`, { params, responseType: 'blob' });
  }

  // ── Reportes mensuales archivados (sección Documentos) ────────────

  /** Lista los reportes mensuales archivados en el sistema (más recientes primero). */
  listarReportesMensuales(): Observable<ReporteMensualResponse[]> {
    if (environment.useMocks) return of([]);
    return this.http.get<ReporteMensualResponse[]>(`${this.base}/reportes`);
  }

  /** Genera (o regenera) y archiva el reporte del mes indicado. */
  generarReporteMensual(anio: number, mes: number): Observable<ReporteMensualResponse> {
    const params = new HttpParams().set('anio', anio).set('mes', mes);
    return this.http.post<ReporteMensualResponse>(`${this.base}/reportes/generar`, null, { params });
  }

  /** Descarga el PDF de un reporte archivado en streaming a través del gateway. */
  descargarReporteMensual(id: number): Observable<Blob> {
    return this.http.get(`${this.base}/reportes/${id}/archivo`, { responseType: 'blob' });
  }

  // ── Cuenta corriente del odontólogo ───────────────────────────────

  /** Store mutable de comprobantes por odontólogo (solo mocks). */
  private ccStore: Record<number, ComprobanteResponse[]> = {};
  private pagosStore: Record<number, PagoCuentaCorrienteResponse[]> = {};
  private nextPagoId = 7000;

  /** Comprobantes (deudas) de un odontólogo. */
  comprobantesPorOdontologo(odontologoId: number): Observable<ComprobanteResponse[]> {
    if (environment.useMocks) {
      return of(clonar(this.mockComprobantes(odontologoId))).pipe(delay(180));
    }
    return this.http.get<ComprobanteResponse[]>(`${this.base}/comprobantes/odontologo/${odontologoId}`);
  }

  /** Registra un pago manual a la cuenta corriente, imputado a las deudas más viejas. */
  registrarPagoCuentaCorriente(odontologoId: number, req: PagoCuentaCorrienteRequest): Observable<PagoCuentaCorrienteResponse> {
    if (environment.useMocks) {
      return of(this.imputarPagoMock(odontologoId, req)).pipe(delay(260));
    }
    return this.http.post<PagoCuentaCorrienteResponse>(`${this.base}/odontologos/${odontologoId}/pagos`, req);
  }

  /** Histórico de pagos a cuenta corriente de un odontólogo. */
  historialPagosOdontologo(odontologoId: number): Observable<PagoCuentaCorrienteResponse[]> {
    if (environment.useMocks) {
      return of(clonar(this.pagosStore[odontologoId] ?? [])).pipe(delay(150));
    }
    return this.http.get<PagoCuentaCorrienteResponse[]>(`${this.base}/odontologos/${odontologoId}/pagos`);
  }

  /** Genera (una vez) comprobantes demo para el odontólogo a partir del ranking. */
  private mockComprobantes(odontologoId: number): ComprobanteResponse[] {
    if (this.ccStore[odontologoId]) return this.ccStore[odontologoId];
    const cc = this.generarRankingMock().find(x => x.odontologoId === odontologoId);
    const trabajos = ['Corona Zirconio', 'Placa Hawley', 'Provisorio Acrílico', 'Disyuntor McNamara', 'Placa de Relajación'];
    const n = cc?.comprobantesPendientes ?? 0;
    const total = cc?.totalDeuda ?? 0;
    const base = n > 0 ? Math.round(total / n) : 0;
    const lista: ComprobanteResponse[] = [];
    for (let i = 0; i < n; i++) {
      const monto = i === n - 1 ? total - base * (n - 1) : base;   // ajusta el redondeo en el último
      lista.push({
        id: odontologoId * 100 + i,
        nroComprobante: `COMP-2026-${String(odontologoId * 100 + i).padStart(4, '0')}`,
        pedidoId: null,
        nroPedido: `GS-2026-${String(odontologoId * 10 + i).padStart(4, '0')}`,
        odontologoId,
        odontologoNombre: cc?.odontologoNombre ?? 'Odontólogo',
        trabajo: trabajos[i % trabajos.length],
        monto,
        montoPagado: 0,
        saldoPendiente: monto,
        estadoPago: 'PENDIENTE',
        fechaEmision: this.diasAtras((cc?.diasSinPagar ?? 30) - i * 5),
        fechaVencimiento: this.diasAtras((cc?.diasSinPagar ?? 30) - i * 5 - 30),
        fechaCobro: null,
        observaciones: null,
      });
    }
    this.ccStore[odontologoId] = lista;
    return lista;
  }

  /** Imputa un pago a los comprobantes del odontólogo (más viejos primero). */
  private imputarPagoMock(odontologoId: number, req: PagoCuentaCorrienteRequest): PagoCuentaCorrienteResponse {
    const comps = this.mockComprobantes(odontologoId)
      .filter(c => c.estadoPago === 'PENDIENTE' || c.estadoPago === 'PARCIAL')
      .sort((a, b) => new Date(a.fechaEmision).getTime() - new Date(b.fechaEmision).getTime());

    let restante = req.monto;
    let imputado = 0;
    let afectados = 0;
    const hoy = new Date().toISOString().slice(0, 10);

    for (const c of comps) {
      if (restante <= 0) break;
      const aplica = Math.min(restante, c.saldoPendiente);
      c.montoPagado += aplica;
      c.saldoPendiente -= aplica;
      c.estadoPago = c.saldoPendiente <= 0 ? 'COBRADO' : 'PARCIAL';
      if (c.estadoPago === 'COBRADO') c.fechaCobro = req.fecha ?? hoy;
      restante -= aplica;
      imputado += aplica;
      afectados++;
    }

    const saldoRestante = this.mockComprobantes(odontologoId)
      .reduce((acc, c) => acc + c.saldoPendiente, 0);

    let mensaje = `Pago imputado a ${afectados} comprobante(s). Saldo restante: $${saldoRestante.toLocaleString('es-AR')}`;
    if (restante > 0) mensaje += `. Excedente no imputado: $${restante.toLocaleString('es-AR')}`;

    const pago: PagoCuentaCorrienteResponse = {
      id: this.nextPagoId++,
      odontologoId,
      odontologoNombre: comps[0]?.odontologoNombre ?? 'Odontólogo',
      monto: req.monto,
      montoImputado: imputado,
      medio: req.medio,
      fecha: req.fecha ?? hoy,
      nota: req.nota ?? null,
      comprobantesAfectados: afectados,
      saldoRestante,
      mensaje,
    };
    (this.pagosStore[odontologoId] ??= []).unshift(pago);
    return pago;
  }
}
