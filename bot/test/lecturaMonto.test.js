'use strict';
// Decisión de confianza del monto (lecturaMonto.js): puro, sin OCR. Correr: npm test (desde bot/)
const test = require('node:test');
const assert = require('node:assert/strict');
const L = require('../lecturaMonto');

const lectura = (monto, confianza = 'alta', confianzaOcr = 96, origen = 'original') =>
  ({ origen, monto, confianza, confianzaOcr });

test('decidirMonto: dos lecturas que coinciden dejan la confianza como estaba', () => {
  const r = L.decidirMonto({ lecturas: [lectura(85000), lectura(85000, 'alta', 95, 'preparada')] });
  assert.deepEqual(r, { monto: 85000, confianza: 'alta', notas: [] });
});

test('decidirMonto: dos lecturas seguras con montos distintos bajan a "media" y dicen cuáles', () => {
  const r = L.decidirMonto({ lecturas: [lectura(65000), lectura(85000, 'alta', 95, 'preparada')] });
  assert.equal(r.monto, 65000);
  assert.equal(r.confianza, 'media');
  assert.match(r.notas[0], /no coinciden \(\$65\.000 vs \$85\.000\)/);
});

test('decidirMonto: una lectura floja que discrepa de una segura no la desmiente', () => {
  const r = L.decidirMonto({ lecturas: [lectura(85000), lectura(20304, 'media', 80, 'preparada')] });
  assert.deepEqual(r, { monto: 85000, confianza: 'alta', notas: [] });
});

test('decidirMonto: si una imagen no encontró nada y la otra sí, se usa la que encontró', () => {
  const r = L.decidirMonto({ lecturas: [lectura(null, 'baja', null), lectura(85000, 'alta', 93, 'preparada')] });
  assert.equal(r.monto, 85000);
  assert.equal(r.confianza, 'alta');
});

test('decidirMonto: sin ningún monto devuelve null y "baja"', () => {
  assert.deepEqual(L.decidirMonto({ lecturas: [lectura(null, 'baja', null)] }), { monto: null, confianza: 'baja', notas: [] });
  assert.deepEqual(L.decidirMonto({ lecturas: [] }), { monto: null, confianza: 'baja', notas: [] });
});

test('decidirMonto: si Tesseract duda de los dígitos baja a "media"', () => {
  const r = L.decidirMonto({ lecturas: [lectura(85000, 'alta', 62)] });
  assert.equal(r.confianza, 'media');
  assert.match(r.notas[0], /62%/);
  assert.equal(L.decidirMonto({ lecturas: [lectura(85000, 'alta', L.UMBRAL_DIGITOS)] }).confianza, 'alta');
});

test('decidirMonto: el monto releído solo (recorte) tiene que coincidir; si no, baja a "media"', () => {
  const lecturas = [lectura(85000)];
  assert.equal(L.decidirMonto({ lecturas, recorte: { monto: 85000 } }).confianza, 'alta');
  assert.equal(L.decidirMonto({ lecturas, recorte: { monto: null } }).confianza, 'alta', 'no poder releerlo no es una contradicción');
  const mal = L.decidirMonto({ lecturas, recorte: { monto: 65000 } });
  assert.equal(mal.confianza, 'media');
  assert.match(mal.notas[0], /releer solo el monto salió \$65\.000 y no \$85\.000/);
});

test('decidirMonto: nunca sube la confianza (un "media" sigue "media" aunque todo coincida)', () => {
  const r = L.decidirMonto({ lecturas: [lectura(85000, 'media'), lectura(85000, 'media', 95, 'preparada')], recorte: { monto: 85000 } });
  assert.equal(r.confianza, 'media');
});

test('elegirBase: la de mayor confianza; a igualdad, la primera', () => {
  const a = lectura(1000, 'media'), b = lectura(2000, 'alta', 90, 'preparada'), c = lectura(3000, 'alta', 90, 'otra');
  assert.equal(L.elegirBase([a, b, c]), b);
  assert.equal(L.elegirBase([lectura(null, 'baja', null)]), null);
});

test('palabraDelMonto: encuentra la palabra aunque traiga el "$", los centavos o letras por dígitos', () => {
  const palabras = [
    { text: 'Monto', confidence: 95, bbox: { x0: 0, y0: 0, x1: 10, y1: 10 } },
    { text: '$85.OOO', confidence: 90, bbox: { x0: 1, y0: 1, x1: 2, y1: 2 } },
  ];
  assert.equal(L.palabraDelMonto(palabras, 85000), palabras[1]);
  assert.equal(L.palabraDelMonto([{ text: '120.000,00', confidence: 91, bbox: {} }], 120000).confidence, 91);
  assert.equal(L.palabraDelMonto(palabras, 12345), null);
  assert.equal(L.palabraDelMonto(undefined, 85000), null);
});

test('montoDeRecorte: lo que sale de releer solo el monto', () => {
  assert.equal(L.montoDeRecorte('85.000'), 85000);
  assert.equal(L.montoDeRecorte('$ 85.000\n'), 85000);
  assert.equal(L.montoDeRecorte('85000'), 85000);
  assert.equal(L.montoDeRecorte('120.000,00'), 120000);
  assert.equal(L.montoDeRecorte('85,000'), 85000);
  assert.equal(L.montoDeRecorte('$'), null);
  assert.equal(L.montoDeRecorte(''), null);
  assert.equal(L.montoDeRecorte('12'), null);
});

test('consensuar: coinciden -> el valor; uno solo lo encontró -> ese; se contradicen -> null con nota', () => {
  assert.deepEqual(L.consensuar(['123456', '123456'], 'N° de operación'), { valor: '123456', notas: [] });
  assert.deepEqual(L.consensuar([null, '123456'], 'N° de operación'), { valor: '123456', notas: [] });
  assert.deepEqual(L.consensuar([null, null], 'fecha'), { valor: null, notas: [] });
  const r = L.consensuar(['98765432101', '9876543210'], 'N° de operación');
  assert.equal(r.valor, null);
  assert.match(r.notas[0], /N° de operación.*no coinciden/);
});

test('contrastarConPie: si el pie y el comprobante dicen montos distintos, baja a "media"', () => {
  assert.deepEqual(L.contrastarConPie({ monto: 85000, confianza: 'alta' }, 85000), { confianza: 'alta', nota: null });
  assert.deepEqual(L.contrastarConPie({ monto: 85000, confianza: 'alta' }, null), { confianza: 'alta', nota: null });
  const r = L.contrastarConPie({ monto: 65000, confianza: 'alta' }, 85000);
  assert.equal(r.confianza, 'media');
  assert.match(r.nota, /pie del mensaje dice \$85\.000 y el comprobante \$65\.000/);
  assert.equal(L.contrastarConPie({ monto: null, confianza: 'baja' }, 85000).confianza, 'baja');
});

test('confianzaDeIA: la IA propone y el texto confirma; si no aparece, queda a revisión', () => {
  const texto = 'Transferencia\n$ 85.000\nOperación 98765432101';
  assert.equal(L.confianzaDeIA(85000, texto, { monto: null }).confianza, 'alta');
  assert.equal(L.confianzaDeIA(85000, 'Total 85000 pesos', { monto: null }).confianza, 'alta');
  const inventado = L.confianzaDeIA(70000, texto, { monto: null });
  assert.equal(inventado.confianza, 'media');
  assert.match(inventado.nota, /IA/);
});

test('confianzaDeIA: un número que solo está dentro de otro (N° de operación, CBU) no lo confirma', () => {
  assert.equal(L.confianzaDeIA(5432, 'Operación 98765432101', { monto: null }).confianza, 'media');
  assert.equal(L.confianzaDeIA(5000, 'CBU 0170099220000067895000', { monto: null }).confianza, 'media');
});

test('confianzaDeIA: si las reglas leyeron otro monto, no se da por seguro', () => {
  const r = L.confianzaDeIA(85000, '$ 65.000', { monto: 65000 });
  assert.equal(r.confianza, 'media');
  assert.match(r.nota, /la IA leyó \$85\.000 y las reglas \$65\.000/);
});
