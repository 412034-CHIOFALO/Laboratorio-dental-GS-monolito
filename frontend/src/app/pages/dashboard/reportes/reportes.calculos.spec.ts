import { PedidoResponse } from '../../../services/pedidos.service';
import { CajaMovimientoResponse, ComprobanteResponse } from '../../../services/finanzas.service';
import {
  calcularCaja, calcularClientes, calcularCobranzas, calcularProduccion, mesesDeLaSerie, parsearFecha, rangoDe,
} from './reportes.calculos';

/** Hoy es lunes 26/10/2026. */
const HOY = new Date(2026, 9, 26, 15, 30);

const pedido = (p: Partial<PedidoResponse>): PedidoResponse => ({
  id: 1, nroPedido: 'PED-1', odontologoId: 1, odontologoNombre: 'Dr. Pérez', paciente: 'Ana', catalogoTrabajoId: null,
  trabajo: 'Corona', tecnicoId: null, tecnicoNombre: null, fechaEntrega: '2026-10-30', estado: 'RECIBIDO', prioridad: 'NORMAL',
  precioAcordado: 10000, observaciones: null, fechaEntregaReal: null, retiradoPor: null, observacionesEntrega: null,
  fechaCreacion: '2026-10-20T10:00:00', fechaUltimaModificacion: '2026-10-20T10:00:00', ...p,
});

const comprobante = (c: Partial<ComprobanteResponse>): ComprobanteResponse => ({
  id: 1, nroComprobante: 'C-1', pedidoId: 1, nroPedido: 'PED-1', odontologoId: 1, odontologoNombre: 'Dr. Pérez', trabajo: 'Corona',
  monto: 10000, montoPagado: 0, saldoPendiente: 10000, estadoPago: 'PENDIENTE', fechaEmision: '2026-10-10', fechaVencimiento: null,
  fechaCobro: null, observaciones: null, ...c,
});

const movimiento = (m: Partial<CajaMovimientoResponse>): CajaMovimientoResponse => ({
  id: 1, tipo: 'INGRESO', tipoCaja: 'FISICA', concepto: 'Cobro', monto: 1000, referencia: null, fechaMovimiento: '2026-10-15', creadoPor: null, ...m,
});

describe('períodos', () => {
  it('una fecha sin hora se lee como día local, no como medianoche UTC', () => {
    const f = parsearFecha('2026-10-01')!;
    expect(f.getDate()).toBe(1);
    expect(f.getMonth()).toBe(9);
  });

  it('este mes va del 1.º a hoy; el mes anterior, del 1.º al último día', () => {
    const mes = rangoDe('mes', HOY);
    expect(mes.desde).toEqual(new Date(2026, 9, 1));
    expect(mes.hasta.getDate()).toBe(26);

    const anterior = rangoDe('mes-anterior', HOY);
    expect(anterior.desde).toEqual(new Date(2026, 8, 1));
    expect(anterior.hasta.getMonth()).toBe(8);
    expect(anterior.hasta.getDate()).toBe(30);
  });

  it('los últimos 3 meses incluyen el actual y los dos anteriores', () => {
    expect(rangoDe('3m', HOY).desde).toEqual(new Date(2026, 7, 1));
  });

  it('el gráfico de evolución muestra al menos seis meses aunque el período sea de uno', () => {
    const meses = mesesDeLaSerie(rangoDe('mes', HOY), HOY);
    expect(meses.length).toBe(6);
    expect(meses[5].clave).toBe('2026-10');
    expect(meses[0].clave).toBe('2026-05');
  });

  it('y nunca más de doce, ni siquiera con todo el historial', () => {
    expect(mesesDeLaSerie(rangoDe('todo', HOY), HOY).length).toBe(12);
  });

  it('con el mes anterior, la serie termina en ese mes', () => {
    const meses = mesesDeLaSerie(rangoDe('mes-anterior', HOY), HOY);
    expect(meses[meses.length - 1].clave).toBe('2026-09');
  });
});

describe('calcularClientes', () => {
  const rango = rangoDe('mes', HOY);

  it('suma lo facturado y cobrado en el período, y la deuda de siempre', () => {
    const r = calcularClientes(
      [pedido({ id: 1 }), pedido({ id: 2, fechaCreacion: '2026-10-22T10:00:00' })],
      [
        comprobante({ id: 1, monto: 30000, montoPagado: 10000, saldoPendiente: 20000 }),
        comprobante({ id: 2, monto: 5000, montoPagado: 0, saldoPendiente: 5000, fechaEmision: '2026-08-01' }),
      ],
      rango, HOY,
    );

    const f = r.filas[0];
    expect(f.pedidos).toBe(2);
    expect(f.facturado).toBe(30000);   // el de agosto no es de este período
    expect(f.cobrado).toBe(10000);
    expect(f.saldo).toBe(25000);       // pero lo que se debe es de todos
    expect(f.ticket).toBe(30000);
  });

  it('un pedido cancelado no cuenta', () => {
    const r = calcularClientes([pedido({ estado: 'CANCELADO' })], [], rango, HOY);
    expect(r.filas.length).toBe(0);
  });

  it('el estado sale de cuánto hace que pidió por última vez', () => {
    const r = calcularClientes(
      [
        pedido({ id: 1, odontologoId: 1, odontologoNombre: 'Activo', fechaCreacion: '2026-10-20T10:00:00' }),
        pedido({ id: 2, odontologoId: 2, odontologoNombre: 'En riesgo', fechaCreacion: '2026-09-01T10:00:00' }),
        pedido({ id: 3, odontologoId: 3, odontologoNombre: 'Inactivo', fechaCreacion: '2026-05-01T10:00:00' }),
      ],
      [], rangoDe('todo', HOY), HOY,
    );
    const estado = (n: string) => r.filas.find(f => f.nombre === n)?.estado;
    expect(estado('Activo')).toBe('ACTIVO');
    expect(estado('En riesgo')).toBe('EN_RIESGO');
    expect(estado('Inactivo')).toBe('INACTIVO');
  });

  it('los que dejaron de pedir hace más de 60 días aparecen aparte, los que más facturaron primero', () => {
    const r = calcularClientes(
      [
        pedido({ id: 1, odontologoId: 1, odontologoNombre: 'Chico', fechaCreacion: '2026-06-01T10:00:00' }),
        pedido({ id: 2, odontologoId: 2, odontologoNombre: 'Grande', fechaCreacion: '2026-06-01T10:00:00' }),
        pedido({ id: 3, odontologoId: 3, odontologoNombre: 'Reciente', fechaCreacion: '2026-10-20T10:00:00' }),
      ],
      [
        comprobante({ id: 1, odontologoId: 1, odontologoNombre: 'Chico', monto: 1000, fechaEmision: '2026-06-02' }),
        comprobante({ id: 2, odontologoId: 2, odontologoNombre: 'Grande', monto: 90000, fechaEmision: '2026-06-02' }),
      ],
      rango, HOY,
    );
    expect(r.dejaronDePedir.map(f => f.nombre)).toEqual(['Grande', 'Chico']);
  });

  it('cuenta los clientes nuevos y qué parte de lo facturado viene de los tres mayores', () => {
    const r = calcularClientes(
      [
        pedido({ id: 1, odontologoId: 1, odontologoNombre: 'A', fechaCreacion: '2026-10-05T10:00:00' }),
        pedido({ id: 2, odontologoId: 2, odontologoNombre: 'B', fechaCreacion: '2026-10-06T10:00:00' }),
        pedido({ id: 3, odontologoId: 3, odontologoNombre: 'C', fechaCreacion: '2026-10-07T10:00:00' }),
        pedido({ id: 4, odontologoId: 4, odontologoNombre: 'D', fechaCreacion: '2026-03-07T10:00:00' }),
        pedido({ id: 5, odontologoId: 4, odontologoNombre: 'D', fechaCreacion: '2026-10-08T10:00:00' }),
      ],
      [
        comprobante({ id: 1, odontologoId: 1, odontologoNombre: 'A', monto: 40000 }),
        comprobante({ id: 2, odontologoId: 2, odontologoNombre: 'B', monto: 30000 }),
        comprobante({ id: 3, odontologoId: 3, odontologoNombre: 'C', monto: 20000 }),
        comprobante({ id: 4, odontologoId: 4, odontologoNombre: 'D', monto: 10000 }),
      ],
      rango, HOY,
    );
    expect(r.nuevos).toBe(3);           // D ya era cliente desde marzo
    expect(r.activos).toBe(4);
    expect(r.concentracionTop3).toBeCloseTo(0.9);
    expect(r.ticketPromedio).toBe(25000);
  });
});

describe('calcularProduccion', () => {
  const rango = rangoDe('mes', HOY);

  it('cuenta los pedidos por situación', () => {
    const r = calcularProduccion([
      pedido({ id: 1, estado: 'RECIBIDO' }),
      pedido({ id: 2, estado: 'EN_PROCESO' }),
      pedido({ id: 3, estado: 'ENTREGADO', fechaEntregaReal: '2026-10-25' }),
      pedido({ id: 4, estado: 'CANCELADO' }),
      pedido({ id: 5, estado: 'RECIBIDO', fechaCreacion: '2026-08-01T10:00:00' }),
    ], rango, HOY);

    expect(r.creados).toBe(4);
    expect(r.enCurso).toBe(2);
    expect(r.entregados).toBe(1);
    expect(r.cancelados).toBe(1);
  });

  it('el porcentaje en término compara la entrega real con la fecha prometida', () => {
    const r = calcularProduccion([
      pedido({ id: 1, estado: 'ENTREGADO', fechaEntrega: '2026-10-25', fechaEntregaReal: '2026-10-24' }),
      pedido({ id: 2, estado: 'ENTREGADO', fechaEntrega: '2026-10-25', fechaEntregaReal: '2026-10-25' }),
      pedido({ id: 3, estado: 'ENTREGADO', fechaEntrega: '2026-10-22', fechaEntregaReal: '2026-10-25' }),
      pedido({ id: 4, estado: 'ENTREGADO', fechaEntrega: '2026-10-22', fechaEntregaReal: '2026-10-26' }),
    ], rango, HOY);

    expect(r.enTermino).toBe(0.5);
  });

  it('sin entregas no hay porcentaje ni promedio: no se inventa un cero', () => {
    const r = calcularProduccion([pedido({ estado: 'RECIBIDO' })], rango, HOY);
    expect(r.enTermino).toBeNull();
    expect(r.diasPromedio).toBeNull();
  });

  it('los días de producción van de que entró el pedido a que se entregó', () => {
    const r = calcularProduccion([
      pedido({ id: 1, estado: 'ENTREGADO', fechaCreacion: '2026-10-10T09:00:00', fechaEntregaReal: '2026-10-14' }),
      pedido({ id: 2, estado: 'ENTREGADO', fechaCreacion: '2026-10-10T09:00:00', fechaEntregaReal: '2026-10-17' }),
    ], rango, HOY);
    expect(r.diasPromedio).toBe(5.5);
  });

  it('los pasados de fecha son los que siguen en curso con la entrega vencida, sin importar el período', () => {
    const r = calcularProduccion([
      pedido({ id: 1, estado: 'EN_PROCESO', fechaEntrega: '2026-10-20', fechaCreacion: '2026-08-01T10:00:00' }),
      pedido({ id: 2, estado: 'LISTO', fechaEntrega: '2026-10-24' }),
      pedido({ id: 3, estado: 'RECIBIDO', fechaEntrega: '2026-10-26' }),
      pedido({ id: 4, estado: 'ENTREGADO', fechaEntrega: '2026-10-01', fechaEntregaReal: '2026-10-01' }),
    ], rango, HOY);

    expect(r.vencidos.map(v => [v.pedido.id, v.diasVencido])).toEqual([[1, 6], [2, 2]]);
  });

  it('agrupa por trabajo y por técnico, y marca los sin asignar', () => {
    const r = calcularProduccion([
      pedido({ id: 1, trabajo: 'Corona', tecnicoNombre: 'Luis', precioAcordado: 10000, prioridad: 'URGENTE' }),
      pedido({ id: 2, trabajo: 'Corona', tecnicoNombre: 'Luis', precioAcordado: 20000 }),
      pedido({ id: 3, trabajo: 'Puente', tecnicoNombre: null, precioAcordado: null }),
    ], rango, HOY);

    expect(r.porTrabajo[0]).toEqual({ trabajo: 'Corona', cantidad: 2, facturado: 30000, urgentes: 1 });
    expect(r.porTecnico.map(t => [t.tecnico, t.pedidos])).toEqual([['Luis', 2], ['Sin asignar', 1]]);
    expect(r.urgentes).toBe(1);
  });
});

describe('calcularCobranzas', () => {
  const rango = rangoDe('mes', HOY);
  const meses = mesesDeLaSerie(rango, HOY);

  it('lo cobrado y lo pendiente son de lo que se emitió en el período', () => {
    const r = calcularCobranzas([
      comprobante({ id: 1, monto: 10000, montoPagado: 4000, saldoPendiente: 6000 }),
      comprobante({ id: 2, monto: 20000, montoPagado: 20000, saldoPendiente: 0, estadoPago: 'COBRADO', fechaEmision: '2026-10-05', fechaCobro: '2026-10-15' }),
      comprobante({ id: 3, monto: 50000, montoPagado: 0, saldoPendiente: 50000, fechaEmision: '2026-07-01' }),
    ], rango, HOY, meses);

    expect(r.facturado).toBe(30000);
    expect(r.cobrado).toBe(24000);
    expect(r.pendiente).toBe(6000);
    expect(r.porcentajeCobrado).toBeCloseTo(0.8);
  });

  it('el plazo de cobro es el promedio entre emisión y cobro', () => {
    const r = calcularCobranzas([
      comprobante({ id: 1, estadoPago: 'COBRADO', saldoPendiente: 0, montoPagado: 10000, fechaEmision: '2026-10-01', fechaCobro: '2026-10-11' }),
      comprobante({ id: 2, estadoPago: 'COBRADO', saldoPendiente: 0, montoPagado: 10000, fechaEmision: '2026-10-02', fechaCobro: '2026-10-22' }),
    ], rango, HOY, meses);
    expect(r.plazoPromedio).toBe(15);
  });

  it('la deuda se reparte por antigüedad, con todos los comprobantes que todavía se deben', () => {
    const r = calcularCobranzas([
      comprobante({ id: 1, saldoPendiente: 1000, fechaEmision: '2026-10-20' }),   // 6 días
      comprobante({ id: 2, saldoPendiente: 2000, fechaEmision: '2026-09-10' }),   // 46 días
      comprobante({ id: 3, saldoPendiente: 3000, fechaEmision: '2026-08-10' }),   // 77 días
      comprobante({ id: 4, saldoPendiente: 4000, fechaEmision: '2026-01-10' }),   // más de 90
      comprobante({ id: 5, saldoPendiente: 0, monto: 9000, montoPagado: 9000, estadoPago: 'COBRADO', fechaEmision: '2026-01-10' }),
    ], rango, HOY, meses);

    expect(r.antiguedad.map(t => t.monto)).toEqual([1000, 2000, 3000, 4000]);
    expect(r.antiguedad.map(t => t.cantidad)).toEqual([1, 1, 1, 1]);
    expect(r.deudaTotal).toBe(10000);
  });

  it('la serie trae un valor por mes, con los meses sin movimiento en cero', () => {
    const r = calcularCobranzas([comprobante({ monto: 8000, montoPagado: 3000, fechaEmision: '2026-09-15' })], rango, HOY, meses);
    expect(r.serie.length).toBe(6);
    const sep = r.serie.find(s => s.mes === 'Sep')!;
    expect(sep.facturado).toBe(8000);
    expect(sep.cobrado).toBe(3000);
    expect(r.serie.find(s => s.mes === 'Ago')!.facturado).toBe(0);
  });

  it('sin comprobantes en el período no hay porcentaje', () => {
    expect(calcularCobranzas([], rango, HOY, meses).porcentajeCobrado).toBeNull();
  });
});

describe('calcularCaja', () => {
  const rango = rangoDe('mes', HOY);
  const meses = mesesDeLaSerie(rango, HOY);

  it('separa lo que entró de lo que salió y deja afuera la caja de compensación', () => {
    const r = calcularCaja([
      movimiento({ id: 1, tipo: 'INGRESO', monto: 50000, tipoCaja: 'FISICA' }),
      movimiento({ id: 2, tipo: 'INGRESO', monto: 30000, tipoCaja: 'BANCARIA' }),
      movimiento({ id: 3, tipo: 'EGRESO', monto: 20000, tipoCaja: 'FISICA' }),
      movimiento({ id: 4, tipo: 'INGRESO', monto: 99999, tipoCaja: 'COMPENSACION' }),
      movimiento({ id: 5, tipo: 'EGRESO', monto: 99999, tipoCaja: 'COMPENSACION' }),
    ], rango, meses);

    expect(r.ingresos).toBe(80000);
    expect(r.egresos).toBe(20000);
    expect(r.neto).toBe(60000);
    expect(r.porCaja).toEqual([
      { caja: 'FISICA', ingresos: 50000, egresos: 20000 },
      { caja: 'BANCARIA', ingresos: 30000, egresos: 0 },
    ]);
  });

  it('los mayores egresos van de mayor a menor y son del período', () => {
    const r = calcularCaja([
      movimiento({ id: 1, tipo: 'EGRESO', monto: 100 }),
      movimiento({ id: 2, tipo: 'EGRESO', monto: 900 }),
      movimiento({ id: 3, tipo: 'EGRESO', monto: 5000, fechaMovimiento: '2026-07-01' }),
    ], rango, meses);

    expect(r.mayoresEgresos.map(m => m.monto)).toEqual([900, 100]);
  });
});
