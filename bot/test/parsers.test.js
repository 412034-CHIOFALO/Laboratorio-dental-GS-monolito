'use strict';
// Parsers del bot: texto de comprobante (como lo devuelve el OCR o pdf-parse) -> datos.
// Correr: npm test (desde bot/)
const test = require('node:test');
const assert = require('node:assert/strict');
const P = require('../parsers');

test('extraerMonto: monto con $ y miles', () => {
  assert.deepEqual(P.extraerMonto('Transferencia enviada\n$ 85.000\n12 de septiembre'), { monto: 85000, confianza: 'alta' });
});

test('extraerMonto: Personal Pay parte el monto en líneas ("$" / "60.000" / "00")', () => {
  assert.equal(P.extraerMonto('Monto\n$\n60.000\n00\nOrigen').monto, 60000);
});

test('extraerMonto: con centavos y millones', () => {
  assert.equal(P.extraerMonto('Importe: $ 1.250.000,50').monto, 1250000);
});

test('extraerMonto: un N° de operación o un CBU no es un monto', () => {
  const r = P.extraerMonto('Número de operación\n98765432101\nCBU 0170099220000067890123');
  assert.equal(r.monto, null);
});

test('extraerMonto: prefiere el que tiene $ o una palabra clave sobre un número suelto', () => {
  const r = P.extraerMonto('Fecha 15.09.2026\nAlias 1.234.567\nTotal: $ 45.000');
  assert.equal(r.monto, 45000);
  assert.equal(r.confianza, 'alta');
});

test('extraerMonto: sin números con formato de dinero devuelve null y confianza baja', () => {
  assert.deepEqual(P.extraerMonto('Hola, te mando el comprobante'), { monto: null, confianza: 'baja' });
});

test('extraerIdOperacion: Mercado Pago, Personal Pay y bancos', () => {
  assert.equal(P.extraerIdOperacion('Número de operación de Mercado Pago\n98765432101'), '98765432101');
  assert.equal(P.extraerIdOperacion('Número de operación 5544332211'), '5544332211');
  assert.equal(P.extraerIdOperacion('Código de identificación: AB12CD34EF'), 'AB12CD34EF');
  assert.equal(P.extraerIdOperacion('Operación: 123-456-789'), '123-456-789');
});

test('extraerIdOperacion: sin dato devuelve null', () => {
  assert.equal(P.extraerIdOperacion('$ 5.000 a Juan'), null);
});

test('extraerDePara: etiqueta sola (Mercado Pago), etiqueta pegada (Personal Pay) y con dos puntos (banco)', () => {
  assert.deepEqual(P.extraerDePara('Para\nCarlos López\nMercado Pago\nDe\nMartín García'),
    { de: 'Martín García', para: 'Carlos López' });
  assert.deepEqual(P.extraerDePara('OrigenMartín García\nDestinoCarlos López'),
    { de: 'Martín García', para: 'Carlos López' });
  assert.deepEqual(P.extraerDePara('Origen: Laura Sánchez\nDestino: Proveedor Insumos SA'),
    { de: 'Laura Sánchez', para: 'Proveedor Insumos SA' });
});

test('parsearPie: Emisor (Receptor) con y sin monto', () => {
  assert.deepEqual(P.parsearPie('Dr. García (Carlos López)'), { emisor: 'Dr. García', receptor: 'Carlos López', montoManual: null });
  assert.equal(P.parsearPie('Dr. García (Carlos López) 85000').montoManual, 85000);
  assert.equal(P.parsearPie('Dr. García (Carlos López) $85.000').montoManual, 85000);
  assert.deepEqual(P.parsearPie('hola a todos'), { emisor: null, receptor: null, montoManual: null });
});

test('parsearPie: un número chico no se toma por monto', () => {
  assert.equal(P.parsearPie('Dr. García (Carlos López) 12').montoManual, null);
});

test('parsearEfectivo: formato "efectivo <monto> (Receptor)"', () => {
  assert.deepEqual(P.parsearEfectivo('efectivo 50000 (Carlos López)'), { monto: 50000, receptor: 'Carlos López' });
  assert.deepEqual(P.parsearEfectivo('Efectivo $50.000 (Proveedor X)'), { monto: 50000, receptor: 'Proveedor X' });
  assert.equal(P.parsearEfectivo('efectivo 50 (Carlos)'), null, 'menos de $100 no es un pago');
  assert.equal(P.parsearEfectivo('pagué en efectivo'), null);
});

test('el aviso de ejemplo del propio bot NO debe pasar por un pie real si se filtra por fromMe', () => {
  // Documenta el riesgo que cubre el filtro msg.fromMe de index.js: este texto SÍ parsea como pie.
  const aviso = '📎 Recibí el comprobante. Ahora mandá quién a quién: *Emisor (Receptor)*\nEj: Dr. García (Carlos López)';
  assert.equal(P.parsearPie(aviso).receptor !== null, true);
});

test('nombresCoinciden: acentos, títulos, prefijos y palabras partidas del PDF', () => {
  assert.equal(P.nombresCoinciden('Dr. Martín García', 'Martin Garcia').ok, true);
  assert.equal(P.nombresCoinciden('Nicolás Pérez', 'Nicol S Pérez').ok, true);
  assert.equal(P.nombresCoinciden('Carlos López', 'Lopez, Carlos').ok, true);
  assert.equal(P.nombresCoinciden('Carlos López', 'Ana Rodríguez').ok, false);
  assert.equal(P.nombresCoinciden(null, 'Ana').ok, false);
});

test('validarPersonas: pasa si coincide todo lo que el comprobante trae; falla si alguien pone un nombre falso', () => {
  const lectura = { de: 'Martín García', para: 'Carlos López' };
  assert.equal(P.validarPersonas({ emisor: 'Dr. García', receptor: 'Carlos López' }, lectura).ok, true);
  const mal = P.validarPersonas({ emisor: 'Dr. García', receptor: 'Pedro Gómez' }, lectura);
  assert.equal(mal.ok, false);
  assert.match(mal.detalle, /receptor/);
  assert.equal(P.validarPersonas({ emisor: 'x', receptor: 'y' }, { de: null, para: null }).ok, true);
});
