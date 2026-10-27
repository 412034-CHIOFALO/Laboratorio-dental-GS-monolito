'use strict';
// OCR de punta a punta: imagen -> Tesseract (varias pasadas) -> parsers. Las imágenes de test/fixtures las
// genera test/generar_fixtures.py con el DISEÑO de cada billetera y datos inventados; las variantes degradadas
// (modo oscuro, giradas por EXIF, borrosas) se fabrican acá con sharp.
// Hace falta spa.traineddata en bot/ (está en el repo). Correr: npm test (desde bot/)
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');
const { crearOcr } = require('../ocr');
const P = require('../parsers');

const FIX = path.join(__dirname, 'fixtures');
let ocr;

test.before(async () => { ocr = await crearOcr({ cachePath: path.join(__dirname, '..') }); });
test.after(async () => { if (ocr) await ocr.cerrar(); });

const fixture = (archivo) => fs.readFileSync(path.join(FIX, archivo));

async function leer(archivo) {
  const texto = await ocr.leerTexto(fixture(archivo));
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

// ── leerImagen: lectura cruzada (original + preparada + relectura del monto) ─────────────────────────────

test('leerImagen: un comprobante limpio sale con confianza alta, sin notas, con N° de operación y fecha', async () => {
  for (const [archivo, monto, op, fecha] of [
    ['mercadopago.png', 85000, '98765432101', '2026-09-12'],
    ['personalpay.png', 60000, '5544332211', '2026-09-15'],
    ['banco.png', 120000, 'AB12CD34EF', '2026-09-18'],
  ]) {
    const r = await ocr.leerImagen(fixture(archivo));
    assert.equal(r.monto, monto, archivo);
    assert.equal(r.confianza, 'alta', archivo);
    assert.deepEqual(r.notas, [], archivo);
    assert.equal(r.idOperacion, op, archivo);
    assert.equal(r.fecha, fecha, archivo);
    assert.equal(r.lecturas.length, 2, `${archivo}: se leyó la original y la preparada`);
    assert.equal(r.recorte.monto, monto, `${archivo}: la relectura del monto coincide`);
  }
});

test('leerImagen: una foto girada por EXIF (que antes perdía el monto) ahora se lee', async () => {
  const girada = await sharp(fixture('mercadopago.png')).rotate(-90).withMetadata({ orientation: 6 }).jpeg().toBuffer();
  const r = await ocr.leerImagen(girada);
  assert.equal(r.monto, 85000);
  assert.equal(r.confianza, 'alta');
  assert.equal(r.idOperacion, '98765432101');
});

test('leerImagen: una captura en modo oscuro se lee igual', async () => {
  const oscuro = await sharp(fixture('personalpay.png')).negate().png().toBuffer();
  const r = await ocr.leerImagen(oscuro);
  assert.equal(r.monto, 60000);
  assert.equal(r.confianza, 'alta');
});

test('leerImagen: una captura chica y comprimida mantiene el monto', async () => {
  const chica = await sharp(fixture('mercadopago.png')).resize({ width: 480 }).jpeg({ quality: 55 }).toBuffer();
  const r = await ocr.leerImagen(chica);
  assert.equal(r.monto, 85000);
});

test('leerImagen: lo que no es un comprobante no inventa monto, N° de operación ni fecha', async () => {
  const r = await ocr.leerImagen(fixture('no_es_comprobante.png'));
  assert.equal(r.monto, null);
  assert.equal(r.confianza, 'baja');
  assert.equal(r.idOperacion, null);
  assert.equal(r.fecha, null);
  assert.equal(r.recorte, null, 'sin monto no hay nada que releer');
});

test('leerImagen: varias a la vez no se pisan (la relectura del monto cambia la configuración del worker)', async () => {
  const rs = await Promise.all(['mercadopago.png', 'banco.png', 'personalpay.png'].map((f) => ocr.leerImagen(fixture(f))));
  assert.deepEqual(rs.map((r) => r.monto), [85000, 120000, 60000]);
  // y después de eso el worker volvió a su configuración normal
  assert.equal((await leer('mercadopago.png')).monto.monto, 85000);
});

test('leerImagen: un archivo que no es una imagen falla en vez de devolver un monto inventado', async () => {
  await assert.rejects(() => ocr.leerImagen(Buffer.from('esto no es una imagen')));
  // y el worker sigue sirviendo después del error
  assert.equal((await leer('banco.png')).monto.monto, 120000);
});
