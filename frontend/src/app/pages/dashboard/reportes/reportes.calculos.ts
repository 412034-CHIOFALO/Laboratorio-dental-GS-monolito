import { PedidoResponse, EstadoPedido } from '../../../services/pedidos.service';
import { CajaMovimientoResponse, ComprobanteResponse, TipoCaja } from '../../../services/finanzas.service';

/**
 * Las cuentas de cada reporte, sin pantalla de por medio: reciben lo que ya trajo el servidor y devuelven números para
 * mostrar. Así se pueden probar con fechas fijas (el «hoy» siempre se pasa de afuera) y la pantalla solo dibuja.
 */

// ── Períodos ──────────────────────────────────────────────────────

export type PeriodoId = 'mes' | 'mes-anterior' | '3m' | '6m' | '12m' | 'anio' | 'todo';

export const PERIODOS: { id: PeriodoId; etiqueta: string }[] = [
  { id: 'mes', etiqueta: 'Este mes' },
  { id: 'mes-anterior', etiqueta: 'Mes anterior' },
  { id: '3m', etiqueta: 'Últimos 3 meses' },
  { id: '6m', etiqueta: 'Últimos 6 meses' },
  { id: '12m', etiqueta: 'Últimos 12 meses' },
  { id: 'anio', etiqueta: 'Este año' },
  { id: 'todo', etiqueta: 'Todo el historial' },
];

/** Desde el primer instante de `desde` hasta el último de `hasta`. */
export interface Rango { desde: Date; hasta: Date; }

export const MES_CORTO = ['Ene', 'Feb', 'Mar', 'Abr', 'May', 'Jun', 'Jul', 'Ago', 'Sep', 'Oct', 'Nov', 'Dic'];

const DIA_MS = 86_400_000;

export function inicioDelDia(f: Date): Date {
  return new Date(f.getFullYear(), f.getMonth(), f.getDate());
}

function finDelDia(f: Date): Date {
  return new Date(f.getFullYear(), f.getMonth(), f.getDate(), 23, 59, 59, 999);
}

/**
 * Una fecha del servidor. «2026-10-26» a secas se lee como día local: new Date() la tomaría como medianoche UTC, que en
 * Argentina es la noche del día anterior, y un comprobante del 1.º de mes caería en el mes pasado.
 */
export function parsearFecha(iso: string | null | undefined): Date | null {
  if (!iso) return null;
  const solo = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  const f = solo ? new Date(Number(solo[1]), Number(solo[2]) - 1, Number(solo[3])) : new Date(iso);
  return isNaN(f.getTime()) ? null : f;
}

export function rangoDe(id: PeriodoId, hoy: Date): Rango {
  const y = hoy.getFullYear();
  const m = hoy.getMonth();
  switch (id) {
    case 'mes': return { desde: new Date(y, m, 1), hasta: finDelDia(hoy) };
    case 'mes-anterior': return { desde: new Date(y, m - 1, 1), hasta: finDelDia(new Date(y, m, 0)) };
    case '3m': return { desde: new Date(y, m - 2, 1), hasta: finDelDia(hoy) };
    case '6m': return { desde: new Date(y, m - 5, 1), hasta: finDelDia(hoy) };
    case '12m': return { desde: new Date(y, m - 11, 1), hasta: finDelDia(hoy) };
    case 'anio': return { desde: new Date(y, 0, 1), hasta: finDelDia(hoy) };
    case 'todo': return { desde: new Date(2000, 0, 1), hasta: finDelDia(hoy) };
  }
}

export function enRango(f: Date | null, r: Rango): boolean {
  return f != null && f >= r.desde && f <= r.hasta;
}

export interface Mes { clave: string; etiqueta: string; desde: Date; hasta: Date; }

/**
 * Los meses que dibujan las barras de evolución: los del período elegido, y como mínimo seis (un solo mes no muestra
 * tendencia) y como máximo doce, terminando en el último mes del período.
 */
export function mesesDeLaSerie(r: Rango, hoy: Date): Mes[] {
  const fin = r.hasta < hoy ? r.hasta : hoy;
  const mesesDelPeriodo = (fin.getFullYear() - r.desde.getFullYear()) * 12 + fin.getMonth() - r.desde.getMonth() + 1;
  const cantidad = Math.min(12, Math.max(6, mesesDelPeriodo));
  const meses: Mes[] = [];
  for (let i = cantidad - 1; i >= 0; i--) {
    const d = new Date(fin.getFullYear(), fin.getMonth() - i, 1);
    meses.push({
      clave: `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`,
      etiqueta: MES_CORTO[d.getMonth()],
      desde: d,
      hasta: finDelDia(new Date(d.getFullYear(), d.getMonth() + 1, 0)),
    });
  }
  return meses;
}

function dias(desde: Date, hasta: Date): number {
  return Math.round((inicioDelDia(hasta).getTime() - inicioDelDia(desde).getTime()) / DIA_MS);
}

function redondear(n: number, decimales = 1): number {
  const k = 10 ** decimales;
  return Math.round(n * k) / k;
}

// ── Clientes ──────────────────────────────────────────────────────

export type EstadoCliente = 'ACTIVO' | 'EN_RIESGO' | 'INACTIVO' | 'SIN_PEDIDOS';

export interface FilaCliente {
  odontologoId: number;
  nombre: string;
  pedidos: number;
  facturado: number;
  cobrado: number;
  /** Lo que debe hoy, sin importar el período. */
  saldo: number;
  /** Facturado dividido la cantidad de comprobantes del período. */
  ticket: number | null;
  ultimoPedido: Date | null;
  diasSinPedir: number | null;
  estado: EstadoCliente;
}

export interface ResultadoClientes {
  filas: FilaCliente[];
  activos: number;
  nuevos: number;
  ticketPromedio: number | null;
  /** Qué parte de lo facturado vino de los tres clientes que más compraron (0 a 1). */
  concentracionTop3: number | null;
  /** Clientes que ya no piden hace más de 60 días, los que más facturaron primero. */
  dejaronDePedir: FilaCliente[];
}

export const DIAS_PARA_SER_CLIENTE_EN_RIESGO = 30;
export const DIAS_PARA_SER_CLIENTE_INACTIVO = 90;
const DIAS_SIN_PEDIR = 60;

interface AcumuladoCliente {
  id: number; nombre: string;
  pedidos: number; facturado: number; cobrado: number; saldo: number; comprobantes: number; facturadoTotal: number;
  primerPedido: Date | null; ultimoPedido: Date | null;
}

export function calcularClientes(
  pedidos: PedidoResponse[], comprobantes: ComprobanteResponse[], rango: Rango, hoy: Date,
): ResultadoClientes {
  const porId = new Map<number, AcumuladoCliente>();
  const de = (id: number, nombre: string): AcumuladoCliente => {
    let a = porId.get(id);
    if (!a) {
      a = { id, nombre, pedidos: 0, facturado: 0, cobrado: 0, saldo: 0, comprobantes: 0, facturadoTotal: 0, primerPedido: null, ultimoPedido: null };
      porId.set(id, a);
    }
    return a;
  };

  for (const p of pedidos) {
    if (p.estado === 'CANCELADO') continue;
    const f = parsearFecha(p.fechaCreacion);
    if (!f) continue;
    const a = de(p.odontologoId, p.odontologoNombre);
    if (enRango(f, rango)) a.pedidos++;
    if (!a.primerPedido || f < a.primerPedido) a.primerPedido = f;
    if (!a.ultimoPedido || f > a.ultimoPedido) a.ultimoPedido = f;
  }

  for (const c of comprobantes) {
    const a = de(c.odontologoId, c.odontologoNombre);
    a.saldo += c.saldoPendiente;
    a.facturadoTotal += c.monto;
    if (enRango(parsearFecha(c.fechaEmision), rango)) {
      a.facturado += c.monto;
      a.cobrado += c.montoPagado;
      a.comprobantes++;
    }
  }

  const aFila = (a: AcumuladoCliente): FilaCliente => {
    const sinPedir = a.ultimoPedido ? dias(a.ultimoPedido, hoy) : null;
    return {
      odontologoId: a.id, nombre: a.nombre, pedidos: a.pedidos, facturado: a.facturado, cobrado: a.cobrado, saldo: a.saldo,
      ticket: a.comprobantes > 0 ? Math.round(a.facturado / a.comprobantes) : null,
      ultimoPedido: a.ultimoPedido, diasSinPedir: sinPedir,
      estado: sinPedir == null ? 'SIN_PEDIDOS'
        : sinPedir <= DIAS_PARA_SER_CLIENTE_EN_RIESGO ? 'ACTIVO'
        : sinPedir <= DIAS_PARA_SER_CLIENTE_INACTIVO ? 'EN_RIESGO' : 'INACTIVO',
    };
  };

  const todos = [...porId.values()];
  const filas = todos
    .filter(a => a.pedidos > 0 || a.facturado > 0)
    .sort((x, y) => y.facturado - x.facturado || y.pedidos - x.pedidos || x.nombre.localeCompare(y.nombre))
    .map(aFila);

  const facturadoTotal = filas.reduce((s, f) => s + f.facturado, 0);
  const comprobantesTotal = todos.filter(a => a.pedidos > 0 || a.facturado > 0).reduce((s, a) => s + a.comprobantes, 0);
  const top3 = filas.slice(0, 3).reduce((s, f) => s + f.facturado, 0);

  return {
    filas,
    activos: filas.filter(f => f.pedidos > 0).length,
    nuevos: todos.filter(a => enRango(a.primerPedido, rango)).length,
    ticketPromedio: comprobantesTotal > 0 ? Math.round(facturadoTotal / comprobantesTotal) : null,
    concentracionTop3: facturadoTotal > 0 ? top3 / facturadoTotal : null,
    dejaronDePedir: todos
      .filter(a => a.ultimoPedido != null && dias(a.ultimoPedido, hoy) > DIAS_SIN_PEDIR)
      .sort((x, y) => y.facturadoTotal - x.facturadoTotal)
      .slice(0, 10)
      .map(aFila),
  };
}

// ── Producción ────────────────────────────────────────────────────

export interface PedidoVencido {
  pedido: PedidoResponse;
  diasVencido: number;
}

export interface FilaTrabajo { trabajo: string; cantidad: number; facturado: number; urgentes: number; }
export interface FilaTecnico { tecnico: string; pedidos: number; entregados: number; enTermino: number | null; }

export interface ResultadoProduccion {
  creados: number;
  entregados: number;
  cancelados: number;
  enCurso: number;
  urgentes: number;
  /** De los entregados, qué parte salió en la fecha prometida o antes (0 a 1). */
  enTermino: number | null;
  /** Días promedio entre que entró el pedido y se entregó. */
  diasPromedio: number | null;
  porEstado: { estado: EstadoPedido; cantidad: number }[];
  porTrabajo: FilaTrabajo[];
  porTecnico: FilaTecnico[];
  /** Pedidos en curso cuya fecha de entrega ya pasó (sin importar el período), los más atrasados primero. */
  vencidos: PedidoVencido[];
}

const ESTADOS_EN_CURSO: EstadoPedido[] = ['RECIBIDO', 'EN_PROCESO', 'CONTROL', 'LISTO'];
export const ORDEN_ESTADOS: EstadoPedido[] = ['RECIBIDO', 'EN_PROCESO', 'CONTROL', 'LISTO', 'ENTREGADO', 'CANCELADO'];

export function calcularProduccion(pedidos: PedidoResponse[], rango: Rango, hoy: Date): ResultadoProduccion {
  const delPeriodo = pedidos.filter(p => enRango(parsearFecha(p.fechaCreacion), rango));
  const entregados = delPeriodo.filter(p => p.estado === 'ENTREGADO');

  const enTerminoDe = (lista: PedidoResponse[]): number | null => {
    const conFecha = lista.filter(p => parsearFecha(p.fechaEntregaReal) && parsearFecha(p.fechaEntrega));
    if (conFecha.length === 0) return null;
    const bien = conFecha.filter(p => inicioDelDia(parsearFecha(p.fechaEntregaReal)!) <= inicioDelDia(parsearFecha(p.fechaEntrega)!));
    return bien.length / conFecha.length;
  };

  const duraciones = entregados
    .map(p => {
      const ini = parsearFecha(p.fechaCreacion);
      const fin = parsearFecha(p.fechaEntregaReal);
      return ini && fin ? dias(ini, fin) : null;
    })
    .filter((d): d is number => d != null && d >= 0);

  const trabajos = new Map<string, FilaTrabajo>();
  for (const p of delPeriodo.filter(x => x.estado !== 'CANCELADO')) {
    const nombre = p.trabajo.trim() || 'Sin especificar';
    const t = trabajos.get(nombre) ?? { trabajo: nombre, cantidad: 0, facturado: 0, urgentes: 0 };
    t.cantidad++;
    t.facturado += p.precioAcordado ?? 0;
    if (p.prioridad === 'URGENTE') t.urgentes++;
    trabajos.set(nombre, t);
  }

  const tecnicos = new Map<string, PedidoResponse[]>();
  for (const p of delPeriodo.filter(x => x.estado !== 'CANCELADO')) {
    const nombre = p.tecnicoNombre?.trim() || 'Sin asignar';
    tecnicos.set(nombre, [...(tecnicos.get(nombre) ?? []), p]);
  }

  const hoyInicio = inicioDelDia(hoy);
  const vencidos = pedidos
    .filter(p => ESTADOS_EN_CURSO.includes(p.estado))
    .map(p => ({ pedido: p, fecha: parsearFecha(p.fechaEntrega) }))
    .filter((x): x is { pedido: PedidoResponse; fecha: Date } => x.fecha != null && inicioDelDia(x.fecha) < hoyInicio)
    .map(x => ({ pedido: x.pedido, diasVencido: dias(x.fecha, hoy) }))
    .sort((a, b) => b.diasVencido - a.diasVencido);

  return {
    creados: delPeriodo.length,
    entregados: entregados.length,
    cancelados: delPeriodo.filter(p => p.estado === 'CANCELADO').length,
    enCurso: delPeriodo.filter(p => ESTADOS_EN_CURSO.includes(p.estado)).length,
    urgentes: delPeriodo.filter(p => p.prioridad === 'URGENTE' && p.estado !== 'CANCELADO').length,
    enTermino: enTerminoDe(entregados),
    diasPromedio: duraciones.length > 0 ? redondear(duraciones.reduce((s, d) => s + d, 0) / duraciones.length) : null,
    porEstado: ORDEN_ESTADOS.map(estado => ({ estado, cantidad: delPeriodo.filter(p => p.estado === estado).length })),
    porTrabajo: [...trabajos.values()].sort((a, b) => b.cantidad - a.cantidad || b.facturado - a.facturado),
    porTecnico: [...tecnicos.entries()]
      .map(([tecnico, lista]) => ({
        tecnico, pedidos: lista.length,
        entregados: lista.filter(p => p.estado === 'ENTREGADO').length,
        enTermino: enTerminoDe(lista.filter(p => p.estado === 'ENTREGADO')),
      }))
      .sort((a, b) => b.pedidos - a.pedidos),
    vencidos,
  };
}

// ── Cobranzas ─────────────────────────────────────────────────────

export interface TramoDeuda { tramo: string; desde: number; hasta: number | null; monto: number; cantidad: number; }

export interface ResultadoCobranzas {
  facturado: number;
  cobrado: number;
  pendiente: number;
  /** Cobrado sobre facturado, de lo emitido en el período (0 a 1). */
  porcentajeCobrado: number | null;
  /** Días promedio entre que se emitió un comprobante y se terminó de cobrar. */
  plazoPromedio: number | null;
  serie: { mes: string; facturado: number; cobrado: number }[];
  /** Lo que se debe hoy, repartido por antigüedad del comprobante. */
  antiguedad: TramoDeuda[];
  deudaTotal: number;
}

const TRAMOS: { tramo: string; desde: number; hasta: number | null }[] = [
  { tramo: 'Hasta 30 días', desde: 0, hasta: 30 },
  { tramo: '31 a 60 días', desde: 31, hasta: 60 },
  { tramo: '61 a 90 días', desde: 61, hasta: 90 },
  { tramo: 'Más de 90 días', desde: 91, hasta: null },
];

export function calcularCobranzas(
  comprobantes: ComprobanteResponse[], rango: Rango, hoy: Date, meses: Mes[],
): ResultadoCobranzas {
  const emitidos = comprobantes.filter(c => enRango(parsearFecha(c.fechaEmision), rango));
  const facturado = emitidos.reduce((s, c) => s + c.monto, 0);
  const cobrado = emitidos.reduce((s, c) => s + c.montoPagado, 0);

  const plazos = comprobantes
    .filter(c => c.estadoPago === 'COBRADO' && enRango(parsearFecha(c.fechaCobro), rango))
    .map(c => {
      const ini = parsearFecha(c.fechaEmision);
      const fin = parsearFecha(c.fechaCobro);
      return ini && fin ? dias(ini, fin) : null;
    })
    .filter((d): d is number => d != null && d >= 0);

  const antiguedad: TramoDeuda[] = TRAMOS.map(t => ({ ...t, monto: 0, cantidad: 0 }));
  for (const c of comprobantes.filter(x => x.saldoPendiente > 0)) {
    const emision = parsearFecha(c.fechaEmision);
    const edad = emision ? Math.max(0, dias(emision, hoy)) : 0;
    const tramo = antiguedad.find(t => edad >= t.desde && (t.hasta == null || edad <= t.hasta)) ?? antiguedad[0];
    tramo.monto += c.saldoPendiente;
    tramo.cantidad++;
  }

  return {
    facturado, cobrado, pendiente: emitidos.reduce((s, c) => s + c.saldoPendiente, 0),
    porcentajeCobrado: facturado > 0 ? cobrado / facturado : null,
    plazoPromedio: plazos.length > 0 ? redondear(plazos.reduce((s, d) => s + d, 0) / plazos.length) : null,
    serie: meses.map(m => {
      const delMes = comprobantes.filter(c => enRango(parsearFecha(c.fechaEmision), { desde: m.desde, hasta: m.hasta }));
      return { mes: m.etiqueta, facturado: delMes.reduce((s, c) => s + c.monto, 0), cobrado: delMes.reduce((s, c) => s + c.montoPagado, 0) };
    }),
    antiguedad,
    deudaTotal: antiguedad.reduce((s, t) => s + t.monto, 0),
  };
}

// ── Caja ──────────────────────────────────────────────────────────

export interface ResultadoCaja {
  ingresos: number;
  egresos: number;
  neto: number;
  serie: { mes: string; ingresos: number; egresos: number }[];
  porCaja: { caja: TipoCaja; ingresos: number; egresos: number }[];
  mayoresEgresos: CajaMovimientoResponse[];
}

/**
 * Lo que entró y salió de la caja física y la bancaria. La caja de compensación no cuenta: ahí cada triangulado anota
 * un ingreso y un egreso iguales, y sumarlos inflaría los dos lados sin que haya entrado ni salido plata.
 */
export function calcularCaja(movimientos: CajaMovimientoResponse[], rango: Rango, meses: Mes[]): ResultadoCaja {
  const reales = movimientos.filter(m => m.tipoCaja !== 'COMPENSACION');
  const delPeriodo = reales.filter(m => enRango(parsearFecha(m.fechaMovimiento), rango));
  const suma = (lista: CajaMovimientoResponse[], tipo: 'INGRESO' | 'EGRESO') =>
    lista.filter(m => m.tipo === tipo).reduce((s, m) => s + m.monto, 0);
  const ingresos = suma(delPeriodo, 'INGRESO');
  const egresos = suma(delPeriodo, 'EGRESO');

  return {
    ingresos, egresos, neto: ingresos - egresos,
    serie: meses.map(m => {
      const delMes = reales.filter(x => enRango(parsearFecha(x.fechaMovimiento), { desde: m.desde, hasta: m.hasta }));
      return { mes: m.etiqueta, ingresos: suma(delMes, 'INGRESO'), egresos: suma(delMes, 'EGRESO') };
    }),
    porCaja: (['FISICA', 'BANCARIA'] as TipoCaja[]).map(caja => {
      const deLaCaja = delPeriodo.filter(m => m.tipoCaja === caja);
      return { caja, ingresos: suma(deLaCaja, 'INGRESO'), egresos: suma(deLaCaja, 'EGRESO') };
    }),
    mayoresEgresos: delPeriodo.filter(m => m.tipo === 'EGRESO').sort((a, b) => b.monto - a.monto).slice(0, 10),
  };
}
