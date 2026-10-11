'use strict';
// Señales para el semáforo de comprobantes: fecha legible y rastros de edición del archivo.
const test = require('node:test');
const assert = require('node:assert/strict');
const { extraerFecha } = require('../parsers');
const { senalesDeEdicion, detectarEditor, fechaPdf } = require('../forense');

test('extraerFecha: los formatos que traen las billeteras y los bancos', () => {
  assert.equal(extraerFecha('12 de septiembre de 2026 - 14:32 h'), '2026-09-12');
  assert.equal(extraerFecha('3 de Setiembre 2026'), '2026-09-03');
  assert.equal(extraerFecha('Fecha: 15/09/2026'), '2026-09-15');
  assert.equal(extraerFecha('Fecha 15-09-26 10:05'), '2026-09-15');
  assert.equal(extraerFecha('15.09.2026'), '2026-09-15');
  assert.equal(extraerFecha('2026-09-15T10:00:00'), '2026-09-15');
});

test('extraerFecha: lo imposible o dudoso da null (pasa a revisión en vez de adivinarse)', () => {
  assert.equal(extraerFecha('42 de septiembre de 2026'), null, 'el OCR leyó 12 como 42');
  assert.equal(extraerFecha('31/02/2026'), null);
  assert.equal(extraerFecha('99/99/2026'), null);
  assert.equal(extraerFecha('Operación 12345678'), null);
  assert.equal(extraerFecha(''), null);
  assert.equal(extraerFecha(null), null);
  assert.equal(extraerFecha('15/09/1999'), null, 'años fuera de rango');
});

test('extraerFecha: con varias fechas toma la primera válida del texto', () => {
  assert.equal(extraerFecha('Emitido 12 de septiembre de 2026. Vence 12/10/2026'), '2026-09-12');
});

test('detectarEditor: encuentra el nombre del programa en los metadatos', () => {
  assert.equal(detectarEditor(Buffer.from('%PDF-1.7 ... /Producer (Adobe Photoshop 25.0) ...', 'latin1')).toLowerCase(), 'photoshop');
  assert.equal(detectarEditor(Buffer.from('xx Canva yy', 'latin1')).toLowerCase(), 'canva');
  assert.equal(detectarEditor(Buffer.from('%PDF-1.4 /Producer (Mercado Pago)', 'latin1')), null);
  assert.equal(detectarEditor(Buffer.alloc(0)), null);
  assert.equal(detectarEditor(null), null);
});

test('fechaPdf: lee el formato de fecha de los PDF', () => {
  assert.equal(fechaPdf("D:20260912143200-03'00'"), Date.UTC(2026, 8, 12, 14, 32, 0));
  assert.equal(fechaPdf('basura'), null);
  assert.equal(fechaPdf(undefined), null);
});

test('senalesDeEdicion: un PDF editado después de generarse y un PDF limpio', () => {
  const sucio = senalesDeEdicion(Buffer.from('%PDF-1.4'), 'application/pdf',
    { Producer: 'iLovePDF', CreationDate: "D:20260912143200", ModDate: "D:20260913090000" });
  assert.ok(sucio.some(s => /modificó después/.test(s)), sucio.join(' | '));
  assert.ok(sucio.some(s => /ilovepdf/i.test(s)), sucio.join(' | '));

  const limpio = senalesDeEdicion(Buffer.from('%PDF-1.4'), 'application/pdf',
    { Producer: 'Skia/PDF m120', CreationDate: "D:20260912143200", ModDate: "D:20260912143200" });
  assert.deepEqual(limpio, []);
});

test('senalesDeEdicion: una imagen con rastro de Photoshop', () => {
  const jpg = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff]), Buffer.from('Exif..Software Adobe Photoshop CC', 'latin1')]);
  assert.ok(senalesDeEdicion(jpg, 'image/jpeg').some(s => /photoshop/i.test(s)));
  assert.deepEqual(senalesDeEdicion(Buffer.from([0x89, 0x50, 0x4e, 0x47]), 'image/png'), []);
});
