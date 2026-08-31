import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { delay } from 'rxjs/operators';
import { environment } from '../../environments/environment';

/**
 * Frecuencia con la que se le paga a un integrante del laboratorio.
 *  - DIARIO:    cobra por día trabajado
 *  - SEMANAL:   cobra una vez por semana
 *  - QUINCENAL: cada 15 días
 *  - MENSUAL:   una vez al mes
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
}

export interface ConfigSueldoRequest {
  frecuencia: FrecuenciaPago;
  montoBase: number;
}

/** Alta de un integrante nuevo en el módulo de sueldos (ms-finanzas tiene su propia tabla, separada de Usuarios). */
export interface CrearEmpleadoRequest {
  usuarioId: number;
  nombre: string;
  rol?: string | null;
  telefono?: string | null;
  frecuencia: FrecuenciaPago;
  montoBase: number;
}

export interface PagoSueldoRequest {
  usuarioId: number;
  monto: number;
  /** Qué hacer si se paga de más respecto al devengado. */
  manejoSobrante: 'DESCONTAR_PROXIMO' | 'CUBRE_LAB' | 'DEVUELVE_EMPLEADO';
  fecha?: string | null;
  nota?: string | null;
}

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

  /** Confirma un pago en efectivo pendiente: aplica el sueldo/deuda y egresa de caja física. */
  confirmarEfectivo(id: number): Observable<RegistroBot> {
    if (environment.useMocks) {
      return of({} as RegistroBot).pipe(delay(200));
    }
    return this.http.post<RegistroBot>(`${this.base}/registros-bot/${id}/confirmar`, {});
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
      // Sin esto el pago manual no aparecía en el mock de "Triangulados": esa
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
        mensaje: `Triangulado manual: Odontólogo #${odontologoId} → ${proveedorNombre}`,
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
  ajustarDevengado(usuarioId: number, nuevoDevengado: number): Observable<EmpleadoSueldo> {
    if (environment.useMocks) {
      const e = this.store.find(x => x.usuarioId === usuarioId);
      if (!e) throw new Error('Empleado no encontrado');
      e.saldoDevengado = nuevoDevengado;
      return of({ ...e }).pipe(delay(150));
    }
    return this.http.patch<EmpleadoSueldo>(`${this.base}/empleados/${usuarioId}/devengado`, { devengado: nuevoDevengado });
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
      { usuarioId: 1, nombre: 'Rebeca González', rol: 'ADMIN',          telefono: '351-655-1001', activo: true, frecuencia: 'MENSUAL',   montoBase: 0,      saldoDevengado: 0,      saldoSobrante: 0, ultimoPago: null },
      { usuarioId: 2, nombre: 'Carlos López',    rol: 'TECNICO',        telefono: '351-655-1002', activo: true, frecuencia: 'SEMANAL',   montoBase: 120000, saldoDevengado: 120000, saldoSobrante: 0, ultimoPago: '2026-05-29' },
      { usuarioId: 3, nombre: 'Mario Giménez',   rol: 'TECNICO',        telefono: '351-655-1003', activo: true, frecuencia: 'DIARIO',    montoBase: 30000,  saldoDevengado: 90000,  saldoSobrante: 0, ultimoPago: '2026-06-03' },
      { usuarioId: 4, nombre: 'Valentina Torres',rol: 'ADMINISTRATIVO', telefono: '351-655-1004', activo: true, frecuencia: 'QUINCENAL', montoBase: 180000, saldoDevengado: 90000,  saldoSobrante: 15000, ultimoPago: '2026-05-31' },
      { usuarioId: 5, nombre: 'Diego Ferreyra',  rol: 'TECNICO',        telefono: '351-655-1005', activo: true, frecuencia: 'SEMANAL',   montoBase: 110000, saldoDevengado: 55000,  saldoSobrante: 0, ultimoPago: '2026-06-01' },
    ];
  }
}
