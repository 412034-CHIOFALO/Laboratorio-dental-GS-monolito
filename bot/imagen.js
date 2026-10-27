// Preparación de imágenes para el OCR (sharp). Aparte de ocr.js para probarlo solo: test/imagen.test.js.
//
// Tesseract rinde mucho mejor con texto oscuro sobre fondo claro, bien contrastado y de tamaño parejo. Las capturas
// de los celulares casi nunca llegan así: modo oscuro, fotos giradas por EXIF, imágenes chicas recomprimidas por
// WhatsApp. Acá se normalizan; ocr.js lee la imagen original Y la preparada y cruza los resultados.
'use strict';

const sharp = require('sharp');

/** Menos que esto de ancho y el texto sale muy chico para Tesseract: se agranda. */
const ANCHO_MIN = 1400;
/** Más que esto no mejora la lectura y la hace lenta. */
const ANCHO_MAX = 2600;
/** Luminancia media (0-255) por debajo de la cual se considera una captura en modo oscuro. */
const LUMINANCIA_OSCURA = 110;
/** Alto al que se lleva el recorte de un monto para releerlo. */
const ALTO_RECORTE = 110;

async function esOscura(buffer) {
  const { channels } = await sharp(buffer).flatten({ background: '#ffffff' }).greyscale().stats();
  return channels[0].mean < LUMINANCIA_OSCURA;
}

/**
 * La imagen tal cual llegó, pero como PNG (mismos píxeles, sin recomprimir). Sirve para dos cosas: que un archivo
 * que no es una imagen falle ACÁ con un error claro (Tesseract lo reporta como un error asíncrono que nadie puede
 * atajar), y que formatos raros de WhatsApp (WebP de los stickers, GIF, HEIC) lleguen a Tesseract en uno que lee.
 * No gira ni escala: las coordenadas que calcule Tesseract valen para este buffer.
 */
async function aPng(buffer) {
  return sharp(buffer, { failOn: 'none' }).png({ compressionLevel: 1 }).toBuffer();
}

/**
 * Orientación corregida (EXIF), en escala de grises, claro sobre oscuro invertido, contraste estirado, tamaño parejo.
 * @returns {Promise<{buffer: Buffer, oscura: boolean}>}
 */
async function prepararImagen(buffer) {
  const girada = await sharp(buffer, { failOn: 'none' }).rotate().toBuffer({ resolveWithObject: true });
  const oscura = await esOscura(girada.data);

  let p = sharp(girada.data).flatten({ background: '#ffffff' }).greyscale();
  if (oscura) p = p.negate();
  p = p.normalise();
  if (girada.info.width < ANCHO_MIN) p = p.resize({ width: ANCHO_MIN, kernel: 'lanczos3' });
  else if (girada.info.width > ANCHO_MAX) p = p.resize({ width: ANCHO_MAX });
  return { buffer: await p.sharpen().png().toBuffer(), oscura };
}

/**
 * Recorta la zona de un monto (bbox de Tesseract: {x0,y0,x1,y1} en píxeles de `buffer`), con un margen, y la deja
 * lista para releerla sola: grises, texto oscuro sobre claro, grande y con borde blanco.
 * `buffer` tiene que ser EXACTAMENTE la imagen sobre la que Tesseract calculó el bbox (no se gira ni se escala).
 */
async function recortarMonto(buffer, bbox) {
  const { width, height } = await sharp(buffer).metadata();
  const alto = Math.max(1, bbox.y1 - bbox.y0);
  // Margen corto a los costados: el bbox ya abarca todo el número, y más margen mete el "$" que lo precede, que el
  // OCR lee como un "5" más ("$ 85.000" -> "585.000").
  const mx = Math.round(Math.max(alto * 0.15, 4));
  const my = Math.round(Math.max(alto * 0.2, 4));
  const left = Math.max(0, bbox.x0 - mx);
  const top = Math.max(0, bbox.y0 - my);
  const w = Math.min(width, bbox.x1 + mx) - left;
  const h = Math.min(height, bbox.y1 + my) - top;
  if (w < 4 || h < 4) throw new Error('recorte vacío');

  const zona = await sharp(buffer).flatten({ background: '#ffffff' }).extract({ left, top, width: w, height: h })
    .toBuffer();
  let p = sharp(zona).greyscale();
  if (await esOscura(zona)) p = p.negate();
  p = p.normalise();
  if (h < ALTO_RECORTE) p = p.resize({ height: ALTO_RECORTE, kernel: 'lanczos3' });
  return p.extend({ top: 24, bottom: 24, left: 24, right: 24, background: '#ffffff' }).png().toBuffer();
}

module.exports = { aPng, prepararImagen, recortarMonto, esOscura };
