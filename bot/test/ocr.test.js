'use strict';
// OCR de punta a punta: imagen -> Tesseract (2 pasadas) -> parsers. Las imágenes de test/fixtures las
// genera test/generar_fixtures.py con el DISEÑO de cada billetera y datos inventados.
// Hace falta spa.traineddata en bot/ (está en el repo). Correr: npm test (desde bot/)
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { crearOcr } = require('../ocr');
const P = require('../parsers');

const FIX = path.join(__dirname, 'fixtures');
let ocr;

test.before(async () => { ocr = await crearOcr({ cachePath: path.join(__dirname, '..') }); });
test.after(async () => { if (ocr) await ocr.cerrar(); });

async function leer(archivo) {
  const texto = await ocr.leerTexto(fs.readFileSync(path.join(FIX, archivo)));
  return { texto, monto: P.extraerMonto(texto), op: P.extraerIdOperacion(texto), ...P.extraerDePara(texto) };
}

test('Mercado Pago: el monto en letra grande se lee (con PSM 6 por defecto se perdía)', async () => {
  const r = await leer('mercadopago.png');
  assert.equal(r.monto.monto, 85000);
  assert.equal(r.monto.confianza, 'alta');
  assert.equal(r.op, '98765432101');
  assert.equal(P.nombresCoinciden(r.de, 'Martín García').ok, true, `De leído: ${r.de}`);
  assert.equal(P.nombresCoinciden(r.para, 'Carlos López').ok, true, `Para leído: ${r.para}`);
});

test('Personal Pay: monto partido en líneas y etiquetas pegadas al nombre', async () => {
  const r = await leer('personalpay.png');
  assert.equal(r.monto.monto, 60000);
  assert.equal(r.op, '5544332211');
  assert.equal(P.nombresCoinciden(r.de, 'Martín García').ok, true, `De leído: ${r.de}`);
  assert.equal(P.nombresCoinciden(r.para, 'Carlos López').ok, true, `Para leído: ${r.para}`);
});

test('Banco: importe con centavos, origen/destino y código de identificación', async () => {
  const r = await leer('banco.png');
  assert.equal(r.monto.monto, 120000);
  assert.equal(r.op, 'AB12CD34EF');
  assert.equal(P.nombresCoinciden(r.de, 'Laura Sánchez').ok, true);
  assert.equal(P.nombresCoinciden(r.para, 'Proveedor Insumos Dentales SA').ok, true);
});

test('Foto inclinada y borrosa del comprobante: el monto y el N° de operación siguen saliendo', async () => {
  const r = await leer('foto_inclinada.jpg');
  assert.equal(r.monto.monto, 85000);
  assert.equal(r.op, '98765432101');
});

test('Una imagen que no es un comprobante no inventa un monto ni un N° de operación', async () => {
  const r = await leer('no_es_comprobante.png');
  assert.equal(r.monto.monto, null);
  assert.equal(r.op, null);
});

test('Dos lecturas simultáneas no se pisan (el worker es uno solo)', async () => {
  const [a, b] = await Promise.all([leer('mercadopago.png'), leer('banco.png')]);
  assert.equal(a.monto.monto, 85000);
  assert.equal(b.monto.monto, 120000);
});
