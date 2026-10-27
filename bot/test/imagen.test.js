'use strict';
// Preparación de imágenes para el OCR (imagen.js). Las variantes (modo oscuro, giradas por EXIF, chicas) se
// fabrican acá con sharp a partir de los fixtures, para no versionar más binarios. Correr: npm test (desde bot/)
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');
const { aPng, prepararImagen, recortarMonto, esOscura } = require('../imagen');

const mp = () => fs.readFileSync(path.join(__dirname, 'fixtures', 'mercadopago.png'));

test('aPng: deja los mismos píxeles (sin girar ni escalar) y rechaza lo que no es una imagen', async () => {
  const jpg = fs.readFileSync(path.join(__dirname, 'fixtures', 'foto_inclinada.jpg'));
  const a = await sharp(jpg).metadata();
  const b = await sharp(await aPng(jpg)).metadata();
  assert.deepEqual([b.format, b.width, b.height], ['png', a.width, a.height]);
  const webp = await sharp(mp()).webp().toBuffer();
  assert.equal((await sharp(await aPng(webp)).metadata()).format, 'png', 'un sticker/WebP llega a Tesseract como PNG');
  await assert.rejects(() => aPng(Buffer.from('esto no es una imagen')));
});

test('esOscura distingue una captura en modo oscuro de una en modo claro', async () => {
  assert.equal(await esOscura(mp()), false);
  assert.equal(await esOscura(await sharp(mp()).negate().png().toBuffer()), true);
});

test('prepararImagen: una captura en modo oscuro vuelve texto oscuro sobre fondo claro', async () => {
  const oscuro = await sharp(mp()).negate().png().toBuffer();
  const r = await prepararImagen(oscuro);
  assert.equal(r.oscura, true);
  assert.equal(await esOscura(r.buffer), false);
});

test('prepararImagen: agranda una imagen chica y no toca el tamaño de una grande', async () => {
  const meta = await sharp(mp()).metadata();
  const chica = await sharp(mp()).resize({ width: 400 }).png().toBuffer();
  assert.ok((await sharp((await prepararImagen(chica)).buffer).metadata()).width >= 1400);
  const grande = (await sharp((await prepararImagen(mp())).buffer).metadata()).width;
  assert.ok(grande >= Math.min(meta.width, 1400) && grande <= 2600, `ancho ${grande}`);
});

test('prepararImagen: respeta la orientación EXIF (una foto girada queda derecha)', async () => {
  const meta = await sharp(mp()).metadata();
  const girada = await sharp(mp()).rotate(-90).withMetadata({ orientation: 6 }).jpeg().toBuffer();
  const r = await sharp((await prepararImagen(girada)).buffer).metadata();
  assert.ok(r.height > r.width, `debería quedar vertical como el original (${meta.width}x${meta.height}), quedó ${r.width}x${r.height}`);
});

test('prepararImagen: acepta una imagen con transparencia', async () => {
  const conAlfa = await sharp({ create: { width: 300, height: 200, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } } })
    .png().toBuffer();
  const r = await prepararImagen(conAlfa);
  assert.ok(Buffer.isBuffer(r.buffer) && r.buffer.length > 0);
});

test('recortarMonto: devuelve la zona agrandada y con borde, sin salirse de la imagen', async () => {
  const z = await recortarMonto(mp(), { x0: 107, y0: 161, x1: 299, y1: 208 });
  const m = await sharp(z).metadata();
  assert.ok(m.height >= 110, `alto ${m.height}`);
  assert.ok(m.width > m.height, 'un monto es más ancho que alto');
  // bbox pegado al borde de la imagen: se recorta al borde, no revienta
  const meta = await sharp(mp()).metadata();
  await recortarMonto(mp(), { x0: meta.width - 30, y0: meta.height - 20, x1: meta.width + 40, y1: meta.height + 20 });
});

test('recortarMonto: un bbox sin superficie falla con un error claro', async () => {
  await assert.rejects(() => recortarMonto(mp(), { x0: 5000, y0: 5000, x1: 5100, y1: 5050 }), /recorte vacío/);
});
