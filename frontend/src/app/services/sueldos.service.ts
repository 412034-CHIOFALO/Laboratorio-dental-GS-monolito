import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { delay } from 'rxjs/operators';
import { environment } from '../../environments/environment';
import { hoyComoLocalDate } from './date-utils';

/**
 * Frecuencia con la que se le paga a un integrante del laboratorio. El sueldo se reparte por los días REALES
 * del ciclo (un mensual cobra su monto por mes, en febrero y en enero).
 *  - DIARIO:    cobra por día trabajado: solo cobra los días que se marcan en el calendario
 *  - SEMANAL:   su monto por semana (lunes a domingo)
 *  - QUINCENAL: su monto por mitad de mes (1 al 15 y 16 al último)
 *  - MENSUAL:   su monto por mes calendario
 * Los que cobran por ciclo cobran todos los días salvo las ausencias que se marquen.
 */
export type FrecuenciaPago = 'DIARIO' | 'SEMANAL' | 'QUINCENAL' | 'MENSUAL';

export type RolEmpleado = 'TECNICO' | 'ADMINISTRATIVO' | 'ADMIN' | 'OTRO';

/**
 * Configuración de sueldo de un integrante del laboratorio.
 * Está ligado a un usuario del sistema (usuarioId → ms-auth).
 *
 *  - saldoDevengado: lo que el laboratorio le debe acumulado (positivo = a favor del empleado)
 *  - saldoSobrante:  lo que se le pagó de MÁS, a descontar del próximo ciclo
 */
export interface EmpleadoSueldo {
  usuarioId: number;
  nombre: string;
  rol: RolEmpleado;
  telefono: string | null;       // sirve también para que el bot lo identifique
  activo: boolean;
  frecuencia: FrecuenciaPago;
  montoBase: number;             // monto por ciclo completo
  saldoDevengado: number;        // lo que el lab le debe hoy
  saldoSobrante: number;         // pagado de más, a descontar
  ultimoPago: string | null;     // fecha ISO del último pago
  fechaAlta: string;             // primer día que corresponde cobrar
  fechaBaja: string | null;      // último día que corresponde cobrar (null = sigue trabajando)
  diasVacacionesAnuales: number; // cupo anual de vacaciones
  devengadoHasta: string | null; // último día ya incluido en el saldo
}

export interface ConfigSueldoRequest {
  frecuencia: FrecuenciaPago;
  montoBase: number;
  diasVacacionesAnuales?: number | null;
}

/** Alta de un integrante nuevo en el módulo de sueldos (ms-finanzas tiene su propia tabla, separada de Usuarios). */
export interface CrearEmpleadoRequest {
  usuarioId: number;
  nombre: string;
  rol?: string | null;
  telefono?: string | null;
  frecuencia: FrecuenciaPago;
  montoBase: number;
  /** Primer día que le corresponde cobrar (si no se manda, hoy). */
  fechaAlta?: string | null;
  diasVacacionesAnuales?: number | null;
}

export interface PagoSueldoRequest {
  usuarioId: number;
  monto: number;
  /** Qué hacer si se paga de más respecto al devengado. */
  manejoSobrante: 'DESCONTAR_PROXIMO' | 'CUBRE_LAB' | 'DEVUELVE_EMPLEADO';
  /** Con qué se pagó: EFECTIVO sale de la Caja Física y TRANSFERENCIA de la Bancaria. */
  medio?: MedioPagoSueldo;
  fecha?: string | null;
  nota?: string | null;
}

export type MedioPagoSueldo = 'EFECTIVO' | 'TRANSFERENCIA';

/** Una línea de la distribución sugerida por el algoritmo de cascada. */
export interface LineaDistribucion {
  empleadoId: number;
  empleadoNombre: string;
  monto: number;
}

/** Propuesta del algoritmo de cascada para distribuir un cobro. */
export interface DistribucionCascada {
  empleados: LineaDistribucion[];
  remanente: number;
  cajaRemanente: 'FISICA' | 'BANCARIA' | 'COMPENSACION';
}

/**
 * Qué pasó un día con un empleado.
 *  - TRABAJADO:     trabajó (se paga; para quien cobra por día es lo que le habilita el cobro)
 *  - AUSENTE:       faltó sin goce de sueldo (no se paga)
 *  - VACACIONES:    se paga y descuenta del cupo anual
 *  - FERIADO:       se paga
 *  - LICENCIA_PAGA: enfermedad, estudio, duelo... se paga
 */
export type TipoDia = 'TRABAJADO' | 'AUSENTE' | 'VACACIONES' | 'FERIADO' | 'LICENCIA_PAGA';

export interface DiaAsistencia {
  fecha: string;
  tipo: TipoDia | null;
  sePaga: boolean;
  /** false si el día cae antes del alta o después de la baja */
  enRelacion: boolean;
}

export interface ResumenAsistencia {
  diasPagos: number;
  diasNoPagos: number;
  trabajados: number;
  ausencias: number;
  vacaciones: number;
  feriados: number;
  licencias: number;
  sueldoDelMes: number;
  vacacionesAnuales: number;
  vacacionesTomadasEnElAnio: number;
  vacacionesDisponibles: number;
}

export interface AsistenciaMes {
  mes: string;                 // AAAA-MM
  empleadoId: number;
  empleadoNombre: string;
  frecuencia: FrecuenciaPago;
  fechaAlta: string;
  fechaBaja: string | null;
  dias: DiaAsistencia[];
  resumen: ResumenAsistencia;
}

export interface MarcarAsistenciaRequest {
  fechas: string[];
  /** null borra la marca: el día vuelve a ser un día común */
  tipo: TipoDia | null;
  nota?: string | null;
}

export interface MarcarAsistenciaResponse {
  diasModificados: number;
  /** cuánto se corrigió el saldo (negativo = se descontó) por los días que ya estaban devengados */
  ajuste: number;
  saldoDevengado: number;
  advertencias: string[];
}

export type TipoMovimientoSueldo = 'DEVENGO' | 'AJUSTE_ASISTENCIA' | 'AJUSTE_MANUAL' | 'PAGO';

/** Una línea del libro de sueldos: por qué y cuánto cambió el saldo de un empleado. */
export interface MovimientoSueldo {
  id: number;
  fecha: string;
  tipo: TipoMovimientoSueldo;
  monto: number;               // con signo
  saldoDevengado: number;
  saldoSobrante: number;
  detalle: string | null;
  usuario: string | null;
  creadoEn: string;
}

export type OrigenPago = 'MANUAL' | 'BOT_WHATSAPP';

/** Item del histórico de pagos. */
export interface PagoSueldoResponse {
  id: number;
  empleadoId: number;
  empleadoNombre: string;
  monto: number;
  fecha: string;
  origen: OrigenPago;
  manejoSobrante: string | null;
  montoExcedente: number;
  nota: string | null;
  cargadoPorNombre: string | null;
  emisor: string | null;
  comprobanteUrl: string | null;
  grupoOrigen: string | null;
  /** Con qué se pagó; null en los pagos anteriores a que se registrara. */
  medio?: MedioPagoSueldo | null;
}

/** Pago triangulado a proveedor cargado a mano desde Cuentas Corrientes: el odontólogo pagó directo al proveedor por el laboratorio. */
export interface PagoTrianguladoProveedorRequest {
  proveedorId: number;
  monto: number;
  nota?: string | null;
}

export interface PagoTrianguladoProveedorResponse {
  odontologoId: number;
  proveedorId: number;
  proveedorNombre: string;
  monto: number;
  settOdontologo: number;
  settProveedor: number;
  mensaje: string;
}

export type TipoReceptorBot = 'EMPLEADO' | 'PROVEEDOR' | 'DESCONOCIDO';
export type EstadoRegistroBot = 'REGISTRADO' | 'RECHAZADO' | 'DUPLICADO' | 'PENDIENTE';
export type FuentePago = 'TRANSFERENCIA' | 'EFECTIVO';

/** Item del historial del bot: qué hizo con cada comprobante que recibió. */
export interface RegistroBot {
  id: number;
  fechaHora: string;
  monto: number | null;
  idOperacion: string | null;
  emisor: string | null;
  receptorNombre: string | null;
  tipoReceptor: TipoReceptorBot | null;
  receptorId: number | null;
  receptorResuelto: string | null;
  estado: EstadoRegistroBot;
  mensaje: string | null;
  cargadoPorNombre: string | null;
  grupoOrigen: string | null;
  tieneComprobante: boolean;
  fuente: FuentePago;
  /** Por qué una transferencia quedó pendiente de revisión (motivos separados por " | "), o null. */
  motivosRevision?: string | null;
  /** Solo en los pendientes: odontólogos con deuda que podrían ser quien pagó, cuando el nombre no alcanza para estar seguro. */
  candidatosEmisor?: CandidatoEmisor[];
}

/** Un odontólogo con deuda que podría ser quien pagó un comprobante. */
export interface CandidatoEmisor {
  odontologoId: number;
  nombre: string;
  deuda: number;
}

/** Lo que decide quien aprueba cuando no se sabe seguro quién pagó. */
export interface DecisionEmisor {
  odontologoId?: number | null;
  /** true = no fue un odontólogo: lo pagó el laboratorio. */
  sinOdontologo?: boolean;
}

@Injectable({ providedIn: 'root' })
export class SueldosService {

  private readonly base = `${environment.gatewayUrl}/api/finanzas/sueldos`;

  // Store mutable en memoria para mocks
  private store: EmpleadoSueldo[] = this.seedMock();
  private pagosMock: PagoSueldoResponse[] = this.seedPagosMock();
  private nextPagoId = 7000;
  private registrosBotMock: RegistroBot[] = this.seedRegistrosBotMock();
  private nextRegistroBotId = 5000;

  constructor(private http: HttpClient) {}

  listarEmpleados(): Observable<EmpleadoSueldo[]> {
    if (environment.useMocks) {
      return of(this.store.map(e => ({ ...e }))).pipe(delay(200));
    }
    return this.http.get<EmpleadoSueldo[]>(`${this.base}/empleados`);
  }

  /** Actualiza manualmente la configuración de sueldo (frecuencia + monto). */
  actualizarConfig(usuarioId: number, req: ConfigSueldoRequest): Observable<EmpleadoSueldo> {
    if (environment.useMocks) {
      const e = this.store.find(x => x.usuarioId === usuarioId);
      if (!e) throw new Error('Empleado no encontrado');
      e.frecuencia = req.frecuencia;
      e.montoBase = req.montoBase;
      return of({ ...e }).pipe(delay(180));
    }
    return this.http.put<EmpleadoSueldo>(`${this.base}/empleados/${usuarioId}/config`, req);
  }

  /** Da de alta a un integrante del laboratorio en el módulo de sueldos. */
  crearEmpleado(req: CrearEmpleadoRequest): Observable<EmpleadoSueldo> {
    if (environment.useMocks) {
      if (this.store.some(x => x.usuarioId === req.usuarioId)) {
        throw new Error('Ese empleado ya está dado de alta en sueldos.');
      }
      const nuevo: EmpleadoSueldo = {
        usuarioId: req.usuarioId,
        nombre: req.nombre,
        rol: (req.rol as RolEmpleado) ?? 'OTRO',
        telefono: req.telefono ?? null,
        activo: true,
        frecuencia: req.frecuencia,
        montoBase: req.montoBase,
        saldoDevengado: 0,
        saldoSobrante: 0,
        ultimoPago: null,
        fechaAlta: req.fechaAlta ?? hoyComoLocalDate(),
        fechaBaja: null,
        diasVacacionesAnuales: req.diasVacacionesAnuales ?? 14,
        devengadoHasta: null,
      };
      this.store.push(nuevo);
      return of({ ...nuevo }).pipe(delay(200));
    }
    return this.http.post<EmpleadoSueldo>(`${this.base}/empleados`, req);
  }

  /** Histórico de pagos de un empleado. */
  historialPagos(usuarioId: number): Observable<PagoSueldoResponse[]> {
    if (environment.useMocks) {
      const lista = this.pagosMock
        .filter(p => p.empleadoId === usuarioId)
        .sort((a, b) => new Date(b.fecha).getTime() - new Date(a.fecha).getTime());
      return of(lista.map(p => ({ ...p }))).pipe(delay(180));
    }
    return this.http.get<PagoSueldoResponse[]>(`${this.base}/empleados/${usuarioId}/pagos`);
  }

  /** Histórico global de pagos. */
  historialPagosGlobal(): Observable<PagoSueldoResponse[]> {
    if (environment.useMocks) {
      const lista = [...this.pagosMock].sort((a, b) => new Date(b.fecha).getTime() - new Date(a.fecha).getTime());
      return of(lista).pipe(delay(180));
    }
    return this.http.get<PagoSueldoResponse[]>(`${this.base}/pagos`);
  }

  /** Descarga el comprobante guardado de un pago, en streaming a través del gateway. */
  descargarComprobante(pagoId: number): Observable<Blob> {
    if (environment.useMocks) {
      return of(new Blob([], { type: 'application/pdf' })).pipe(delay(150));
    }
    return this.http.get(`${this.base}/pagos/${pagoId}/comprobante/archivo`, { responseType: 'blob' });
  }

  /** Historial de TODO lo que procesó el bot (sueldos, proveedores y rechazos). */
  registrosBot(): Observable<RegistroBot[]> {
    if (environment.useMocks) {
      return of(this.registrosBotMock.map(r => ({ ...r }))).pipe(delay(220));
    }
    return this.http.get<RegistroBot[]>(`${this.base}/registros-bot`);
  }

  /** Descarga el comprobante de un registro del bot, en streaming a través del gateway. */
  descargarComprobanteRegistro(id: number): Observable<Blob> {
    if (environment.useMocks) {
      return of(new Blob([], { type: 'application/pdf' })).pipe(delay(150));
    }
    return this.http.get(`${this.base}/registros-bot/${id}/comprobante/archivo`, { responseType: 'blob' });
  }

  /** Registros del bot en estado PENDIENTE (efectivo sin confirmar). */
  pendientesEfectivo(): Observable<RegistroBot[]> {
    if (environment.useMocks) {
      return of([]).pipe(delay(150));
    }
    return this.http.get<RegistroBot[]>(`${this.base}/pendientes-efectivo`);
  }

  /** Transferencias que el semáforo no dio por buenas: esperan que alguien las apruebe o rechace. */
  pendientesRevision(): Observable<RegistroBot[]> {
    if (environment.useMocks) {
      return of([]).pipe(delay(150));
    }
    return this.http.get<RegistroBot[]>(`${this.base}/pendientes-revision`);
  }

  /** Confirma un pago en efectivo pendiente: aplica el sueldo/deuda y egresa de caja física. */
  confirmarEfectivo(id: number, decision?: DecisionEmisor): Observable<RegistroBot> {
    if (environment.useMocks) {
      return of({} as RegistroBot).pipe(delay(200));
    }
    return this.http.post<RegistroBot>(`${this.base}/registros-bot/${id}/confirmar`, decision ?? {});
  }

  /** Rechaza un pago en efectivo pendiente con motivo opcional. */
  rechazarEfectivo(id: number, motivo?: string): Observable<RegistroBot> {
    if (environment.useMocks) {
      return of({} as RegistroBot).pipe(delay(200));
    }
    return this.http.post<RegistroBot>(
      `${this.base}/registros-bot/${id}/rechazar`,
      motivo ? { motivo } : {}
    );
  }

  private seedRegistrosBotMock(): RegistroBot[] {
    const now = Date.now();
    const min = (m: number) => new Date(now - m * 60000).toISOString();
    return [
      { id: 4, fechaHora: min(2),   monto: 520,   idOperacion: '140949489171', emisor: 'Nicolás Chiofalo', receptorNombre: 'Luciano Giménez', tipoReceptor: 'PROVEEDOR',   receptorId: 2,    receptorResuelto: 'Luciano Giménez', estado: 'REGISTRADO', mensaje: 'Pago a proveedor registrado: Luciano Giménez', cargadoPorNombre: 'Vos', grupoOrigen: 'Prueba', tieneComprobante: true, fuente: 'TRANSFERENCIA' },
      { id: 3, fechaHora: min(35),  monto: 30000, idOperacion: '998877',        emisor: 'Laboratorio GS',   receptorNombre: 'Carlos',          tipoReceptor: 'EMPLEADO',    receptorId: 2,    receptorResuelto: 'Carlos López',     estado: 'REGISTRADO', mensaje: 'Sueldo registrado para Carlos López', cargadoPorNombre: 'Vos', grupoOrigen: 'Comprobantes Transferencias', tieneComprobante: true, fuente: 'TRANSFERENCIA' },
      { id: 2, fechaHora: min(90),  monto: 1500,  idOperacion: '112233',        emisor: 'Dr. García',       receptorNombre: 'gimenez',         tipoReceptor: 'DESCONOCIDO', receptorId: null, receptorResuelto: null,               estado: 'RECHAZADO',  mensaje: 'No se encontró ningún empleado ni proveedor que coincida con "gimenez"', cargadoPorNombre: 'Vos', grupoOrigen: 'Prueba', tieneComprobante: true, fuente: 'TRANSFERENCIA' },
      { id: 1, fechaHora: min(140), monto: 520,   idOperacion: '140949489171', emisor: 'Nicolás Chiofalo', receptorNombre: 'Luciano Giménez', tipoReceptor: 'DESCONOCIDO', receptorId: null, receptorResuelto: null,               estado: 'DUPLICADO',  mensaje: 'El comprobante (operación 140949489171) ya fue registrado antes.', cargadoPorNombre: 'Vos', grupoOrigen: 'Prueba', tieneComprobante: true, fuente: 'TRANSFERENCIA' },
    ];
  }

  /** Registra un pago al empleado, ajustando devengado/sobrante. */
  registrarPago(req: PagoSueldoRequest): Observable<EmpleadoSueldo> {
    if (environment.useMocks) {
      const e = this.store.find(x => x.usuarioId === req.usuarioId);
      if (!e) throw new Error('Empleado no encontrado');

      let excedente = 0;
      const restante = e.saldoDevengado - req.monto;
      if (restante >= 0) {
        e.saldoDevengado = restante;
      } else {
        excedente = Math.abs(restante);
        e.saldoDevengado = 0;
        if (req.manejoSobrante === 'DESCONTAR_PROXIMO') e.saldoSobrante += excedente;
      }
      e.ultimoPago = req.fecha ?? new Date().toISOString().slice(0, 10);

      // Guardar en el histórico mock
      this.pagosMock.unshift({
        id: this.nextPagoId++,
        empleadoId: e.usuarioId,
        empleadoNombre: e.nombre,
        monto: req.monto,
        fecha: e.ultimoPago,
        origen: 'MANUAL',
        manejoSobrante: req.manejoSobrante,
        montoExcedente: excedente,
        nota: req.nota ?? null,
        cargadoPorNombre: null,
        emisor: null,
        comprobanteUrl: null,
        grupoOrigen: null,
        medio: req.medio ?? 'EFECTIVO',
      });
      return of({ ...e }).pipe(delay(220));
    }
    return this.http.post<EmpleadoSueldo>(`${this.base}/pago`, req);
  }

  /**
   * Registra a mano que un odontólogo pagó directo a un proveedor por el
   * laboratorio: salda su cuenta corriente y la deuda del proveedor por el
   * mismo importe (mismo mecanismo que ya aplica el bot cuando detecta esto
   * en un comprobante de WhatsApp).
   */
  registrarPagoTrianguladoProveedor(odontologoId: number, req: PagoTrianguladoProveedorRequest): Observable<PagoTrianguladoProveedorResponse> {
    if (environment.useMocks) {
      const nombres: Record<number, string> = { 1: 'Dental Import SRL', 2: 'Luciano Giménez', 3: 'Protésica del Sur' };
      const proveedorNombre = nombres[req.proveedorId] ?? 'Proveedor';
      // Sin esto el pago manual no aparecía en el mock de "Pagos registrados": esa
      // tabla se arma leyendo registrosBot(), y este flujo no dejaba nada ahí.
      this.registrosBotMock.unshift({
        id: this.nextRegistroBotId++,
        fechaHora: new Date().toISOString(),
        monto: req.monto,
        idOperacion: null,
        emisor: `Odontólogo #${odontologoId}`,
        receptorNombre: proveedorNombre,
        tipoReceptor: 'PROVEEDOR',
        receptorId: req.proveedorId,
        receptorResuelto: proveedorNombre,
        estado: 'REGISTRADO',
        mensaje: `Pago directo: Odontólogo #${odontologoId} → ${proveedorNombre}`,
        cargadoPorNombre: null,
        grupoOrigen: null,
        tieneComprobante: false,
        fuente: 'TRANSFERENCIA',
      });
      return of({
        odontologoId,
        proveedorId: req.proveedorId,
        proveedorNombre,
        monto: req.monto,
        settOdontologo: req.monto,
        settProveedor: req.monto,
        mensaje: `Pago registrado: se saldó ${proveedorNombre} por $${req.monto} a cuenta del odontólogo.`,
      }).pipe(delay(220));
    }
    return this.http.post<PagoTrianguladoProveedorResponse>(`${this.base}/odontologos/${odontologoId}/pago-proveedor`, req);
  }

  /**
   * Algoritmo de cascada: sugiere cómo distribuir un cobro (primero a los
   * empleados con devengado pendiente, el resto a la caja indicada). Es solo
   * un cálculo — no registra nada por sí solo.
   */
  sugerirCascada(monto: number, cajaRemanente: 'FISICA' | 'BANCARIA' | 'COMPENSACION'): Observable<DistribucionCascada> {
    if (environment.useMocks) {
      let restante = monto;
      const empleados: LineaDistribucion[] = [];
      for (const e of [...this.store].sort((a, b) => a.nombre.localeCompare(b.nombre))) {
        if (restante <= 0) break;
        if (e.saldoDevengado <= 0) continue;
        const asignado = Math.min(e.saldoDevengado, restante);
        empleados.push({ empleadoId: e.usuarioId, empleadoNombre: e.nombre, monto: asignado });
        restante -= asignado;
      }
      return of({ empleados, remanente: restante, cajaRemanente }).pipe(delay(180));
    }
    const params = new HttpParams().set('monto', monto).set('cajaRemanente', cajaRemanente);
    return this.http.get<DistribucionCascada>(`${this.base}/cascada/sugerir`, { params });
  }

  /** Ajuste manual del saldo devengado (por si el cálculo automático no cuadra). */
  ajustarDevengado(usuarioId: number, nuevoDevengado: number, motivo: string): Observable<EmpleadoSueldo> {
    if (environment.useMocks) {
      const e = this.store.find(x => x.usuarioId === usuarioId);
      if (!e) throw new Error('Empleado no encontrado');
      e.saldoDevengado = nuevoDevengado;
      return of({ ...e }).pipe(delay(150));
    }
    return this.http.patch<EmpleadoSueldo>(`${this.base}/empleados/${usuarioId}/devengado`, { devengado: nuevoDevengado, motivo });
  }

  // ── Asistencia, vacaciones, libro de sueldos, alta y baja ─────────

  /** El calendario de un mes de un empleado ('AAAA-MM'), con su resumen. */
  asistenciaMes(usuarioId: number, mes: string): Observable<AsistenciaMes> {
    if (environment.useMocks) {
      return of(this.armarMesMock(usuarioId, mes)).pipe(delay(150));
    }
    const params = new HttpParams().set('mes', mes);
    return this.http.get<AsistenciaMes>(`${this.base}/empleados/${usuarioId}/asistencia`, { params });
  }

  /** Marca (o, con tipo null, desmarca) varios días de una vez. */
  marcarAsistencia(usuarioId: number, req: MarcarAsistenciaRequest): Observable<MarcarAsistenciaResponse> {
    if (environment.useMocks) {
      const marcas = this.marcasMock.get(usuarioId) ?? new Map<string, TipoDia>();
      for (const f of req.fechas) { if (req.tipo) marcas.set(f, req.tipo); else marcas.delete(f); }
      this.marcasMock.set(usuarioId, marcas);
      return of({ diasModificados: req.fechas.length, ajuste: 0, saldoDevengado: 0, advertencias: [] }).pipe(delay(150));
    }
    return this.http.put<MarcarAsistenciaResponse>(`${this.base}/empleados/${usuarioId}/asistencia`, req);
  }

  /** Libro de sueldos de un empleado: los últimos movimientos que cambiaron su saldo. */
  movimientos(usuarioId: number, limite = 100): Observable<MovimientoSueldo[]> {
    if (environment.useMocks) {
      return of([] as MovimientoSueldo[]).pipe(delay(120));
    }
    const params = new HttpParams().set('limite', limite);
    return this.http.get<MovimientoSueldo[]>(`${this.base}/empleados/${usuarioId}/movimientos`, { params });
  }

  /** Da de baja a un empleado: fechaBaja es su último día de trabajo (vacío = hoy). */
  darDeBaja(usuarioId: number, fechaBaja: string | null, motivo: string | null): Observable<EmpleadoSueldo> {
    if (environment.useMocks) {
      const e = this.store.find(x => x.usuarioId === usuarioId);
      if (!e) throw new Error('Empleado no encontrado');
      e.activo = false;
      e.fechaBaja = fechaBaja ?? hoyComoLocalDate();
      return of({ ...e }).pipe(delay(150));
    }
    return this.http.post<EmpleadoSueldo>(`${this.base}/empleados/${usuarioId}/baja`, { fechaBaja, motivo });
  }

  /** Reincorpora a un empleado dado de baja: desde es su primer día de vuelta (vacío = hoy). */
  reactivar(usuarioId: number, desde: string | null): Observable<EmpleadoSueldo> {
    if (environment.useMocks) {
      const e = this.store.find(x => x.usuarioId === usuarioId);
      if (!e) throw new Error('Empleado no encontrado');
      e.activo = true;
      e.fechaBaja = null;
      e.fechaAlta = desde ?? hoyComoLocalDate();
      return of({ ...e }).pipe(delay(150));
    }
    return this.http.post<EmpleadoSueldo>(`${this.base}/empleados/${usuarioId}/reactivar`, { desde });
  }

  // Marcas de asistencia en memoria (solo para el modo de demostración).
  private marcasMock = new Map<number, Map<string, TipoDia>>();

  private armarMesMock(usuarioId: number, mes: string): AsistenciaMes {
    const e = this.store.find(x => x.usuarioId === usuarioId);
    if (!e) throw new Error('Empleado no encontrado');
    const [anio, m] = mes.split('-').map(Number);
    const cantidad = new Date(anio, m, 0).getDate();
    const marcas = this.marcasMock.get(usuarioId) ?? new Map<string, TipoDia>();
    const dias: DiaAsistencia[] = [];
    let pagos = 0;
    for (let d = 1; d <= cantidad; d++) {
      const fecha = `${mes}-${String(d).padStart(2, '0')}`;
      const tipo = marcas.get(fecha) ?? null;
      const enRelacion = fecha >= e.fechaAlta && (!e.fechaBaja || fecha <= e.fechaBaja);
      const sePaga = enRelacion && (tipo ? tipo !== 'AUSENTE' : e.frecuencia !== 'DIARIO');
      if (sePaga) pagos++;
      dias.push({ fecha, tipo, sePaga, enRelacion });
    }
    const cuenta = (t: TipoDia) => dias.filter(x => x.tipo === t && x.enRelacion).length;
    const porDia = e.frecuencia === 'DIARIO' ? e.montoBase : e.montoBase / (e.frecuencia === 'MENSUAL' ? cantidad : e.frecuencia === 'QUINCENAL' ? 15 : 7);
    return {
      mes, empleadoId: e.usuarioId, empleadoNombre: e.nombre, frecuencia: e.frecuencia,
      fechaAlta: e.fechaAlta, fechaBaja: e.fechaBaja, dias,
      resumen: {
        diasPagos: pagos, diasNoPagos: dias.filter(x => x.enRelacion).length - pagos,
        trabajados: cuenta('TRABAJADO'), ausencias: cuenta('AUSENTE'), vacaciones: cuenta('VACACIONES'),
        feriados: cuenta('FERIADO'), licencias: cuenta('LICENCIA_PAGA'),
        sueldoDelMes: Math.round(porDia * pagos * 100) / 100,
        vacacionesAnuales: e.diasVacacionesAnuales, vacacionesTomadasEnElAnio: cuenta('VACACIONES'),
        vacacionesDisponibles: Math.max(0, e.diasVacacionesAnuales - cuenta('VACACIONES')),
      },
    };
  }

  /**
   * Fuerza el cálculo de devengado diario ahora mismo (en vez de esperar al cron
   * de las 00:05). Idempotente — se puede llamar varias veces sin duplicar nada.
   */
  devengarAhora(): Observable<void> {
    if (environment.useMocks) {
      return of(undefined).pipe(delay(200));
    }
    return this.http.post<void>(`${this.base}/devengar-ahora`, {});
  }

  // ── Comprobantes demo (mock) — algunos del bot, algunos manuales ──
  private seedPagosMock(): PagoSueldoResponse[] {
    const d = (n: number) => { const x = new Date(); x.setDate(x.getDate() - n); return x.toISOString().slice(0, 10); };
    return [
      { id: 6001, empleadoId: 2, empleadoNombre: 'Carlos López', monto: 60000, fecha: d(1), origen: 'BOT_WHATSAPP', manejoSobrante: null, montoExcedente: 0, nota: null, cargadoPorNombre: 'Mario Giménez', emisor: 'Dr. Delgado', comprobanteUrl: 'comprobantes/2026/06/abc.pdf', grupoOrigen: 'Comprobantes Transferencias' },
      { id: 6002, empleadoId: 3, empleadoNombre: 'Mario Giménez', monto: 30000, fecha: d(2), origen: 'BOT_WHATSAPP', manejoSobrante: null, montoExcedente: 0, nota: null, cargadoPorNombre: 'Valentina Torres', emisor: 'Dra. Sánchez', comprobanteUrl: 'comprobantes/2026/06/def.pdf', grupoOrigen: 'Comprobantes Transferencias' },
      { id: 6003, empleadoId: 4, empleadoNombre: 'Valentina Torres', monto: 90000, fecha: d(3), origen: 'MANUAL', manejoSobrante: 'DESCONTAR_PROXIMO', montoExcedente: 0, nota: 'Pago quincena', cargadoPorNombre: null, emisor: 'Dr. García', comprobanteUrl: null, grupoOrigen: null },
      { id: 6004, empleadoId: 2, empleadoNombre: 'Carlos López', monto: 50000, fecha: d(5), origen: 'BOT_WHATSAPP', manejoSobrante: null, montoExcedente: 0, nota: null, cargadoPorNombre: 'Mario Giménez', emisor: 'Dr. Delgado', comprobanteUrl: 'comprobantes/2026/06/ghi.jpg', grupoOrigen: 'Comprobantes Efectivo' },
    ];
  }

  // ── Datos demo (mock) — integrantes del laboratorio ──────────────
  private seedMock(): EmpleadoSueldo[] {
    return [
      { usuarioId: 1, nombre: 'Rebeca González', rol: 'ADMIN',          telefono: '351-655-1001', activo: true, frecuencia: 'MENSUAL',   montoBase: 0,      saldoDevengado: 0,      saldoSobrante: 0, ultimoPago: null, fechaAlta: '2026-01-01', fechaBaja: null, diasVacacionesAnuales: 14, devengadoHasta: null },
      { usuarioId: 2, nombre: 'Carlos López',    rol: 'TECNICO',        telefono: '351-655-1002', activo: true, frecuencia: 'SEMANAL',   montoBase: 120000, saldoDevengado: 120000, saldoSobrante: 0, ultimoPago: '2026-05-29', fechaAlta: '2026-01-01', fechaBaja: null, diasVacacionesAnuales: 14, devengadoHasta: null },
      { usuarioId: 3, nombre: 'Mario Giménez',   rol: 'TECNICO',        telefono: '351-655-1003', activo: true, frecuencia: 'DIARIO',    montoBase: 30000,  saldoDevengado: 90000,  saldoSobrante: 0, ultimoPago: '2026-06-03', fechaAlta: '2026-01-01', fechaBaja: null, diasVacacionesAnuales: 14, devengadoHasta: null },
      { usuarioId: 4, nombre: 'Valentina Torres',rol: 'ADMINISTRATIVO', telefono: '351-655-1004', activo: true, frecuencia: 'QUINCENAL', montoBase: 180000, saldoDevengado: 90000,  saldoSobrante: 15000, ultimoPago: '2026-05-31', fechaAlta: '2026-01-01', fechaBaja: null, diasVacacionesAnuales: 14, devengadoHasta: null },
      { usuarioId: 5, nombre: 'Diego Ferreyra',  rol: 'TECNICO',        telefono: '351-655-1005', activo: true, frecuencia: 'SEMANAL',   montoBase: 110000, saldoDevengado: 55000,  saldoSobrante: 0, ultimoPago: '2026-06-01', fechaAlta: '2026-01-01', fechaBaja: null, diasVacacionesAnuales: 14, devengadoHasta: null },
    ];
  }
}
