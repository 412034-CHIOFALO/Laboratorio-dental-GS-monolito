// Mide qué tan bien lee el bot una carpeta de comprobantes REALES. Todo corre local: no usa Gemini, no toca el
// sistema ni manda nada a ningún lado.
//
//   npm run medir -- <carpeta>
//
// La carpeta tiene los comprobantes (jpg, png, pdf...) y un esperados.json con lo que dice cada uno:
//   { "mp-001.jpg": { "monto": 85000, "idOperacion": "98765432101", "fecha": "2026-09-12" },
//     "uala-002.png": { "monto": 120000 } }
// idOperacion y fecha son opcionales. Los comprobantes tienen nombres y datos de cuenta de terceros: mantené esa
// carpeta FUERA del repositorio (o en bot/muestras/, que está ignorada por git).
//
// Lo que importa del resultado no es el porcentaje de aciertos sino los "errores con confianza alta": un monto mal
// leído que el bot daría por bueno y registraría solo. Tiene que ser CERO; si no, hay que mirar ese comprobante.
'use strict';

const fs = require('fs');
const path = require('path');
const pdfParse = require('pdf-parse');
const { crearOcr } = require('./ocr');
const { extraerMonto, extraerIdOperacion, extraerFecha } = require('./parsers');

const EXT = new Set(['.jpg', '.jpeg', '.png', '.webp', '.pdf', '.gif', '.tif', '.tiff']);

async function leerArchivo(ocr, archivo) {
  const buffer = fs.readFileSync(archivo);
  if (path.extname(archivo).toLowerCase() === '.pdf') {
    const texto = (await pdfParse(buffer)).text || '';
    const m = extraerMonto(texto);
    return { ...m, idOperacion: extraerIdOperacion(texto), fecha: extraerFecha(texto), notas: [] };
  }
  return ocr.leerImagen(buffer);
}

async function main() {
  const carpeta = process.argv[2];
  if (!carpeta || !fs.existsSync(carpeta)) {
    console.error('Uso: npm run medir -- <carpeta con comprobantes y esperados.json>');
    process.exit(2);
  }
  const rutaEsperados = path.join(carpeta, 'esperados.json');
  if (!fs.existsSync(rutaEsperados)) {
    console.error(`Falta ${rutaEsperados} (ver el encabezado de medir.js).`);
    process.exit(2);
  }
  const esperados = JSON.parse(fs.readFileSync(rutaEsperados, 'utf8'));
  const archivos = fs.readdirSync(carpeta).filter((f) => EXT.has(path.extname(f).toLowerCase())).sort();
  const sinEsperado = archivos.filter((f) => !esperados[f]);
  if (sinEsperado.length) console.warn(`Sin entrada en esperados.json (se saltean): ${sinEsperado.join(', ')}`);

  const ocr = await crearOcr({ cachePath: __dirname });
  const filas = [];
  for (const f of archivos.filter((a) => esperados[a])) {
    const esp = esperados[f];
    const t0 = Date.now();
    let r;
    try {
      r = await leerArchivo(ocr, path.join(carpeta, f));
    } catch (e) {
      r = { monto: null, confianza: 'baja', idOperacion: null, fecha: null, notas: [`error: ${e.message}`] };
    }
    filas.push({
      archivo: f, esp, r, ms: Date.now() - t0,
      montoOk: r.monto === esp.monto,
      idOk: esp.idOperacion === undefined ? null : r.idOperacion === esp.idOperacion,
      fechaOk: esp.fecha === undefined ? null : r.fecha === esp.fecha,
    });
  }
  await ocr.cerrar();

  for (const x of filas) {
    const marca = x.montoOk ? 'OK ' : (x.r.monto ? 'MAL' : '---');
    console.log(`${marca} ${x.archivo.padEnd(28)} esperado ${String(x.esp.monto).padEnd(9)} leído ${String(x.r.monto).padEnd(9)} ` +
      `${String(x.r.confianza).padEnd(6)} ${String(x.ms).padStart(5)}ms` +
      (x.idOk === false ? `  id: ${x.r.idOperacion}≠${x.esp.idOperacion}` : '') +
      (x.fechaOk === false ? `  fecha: ${x.r.fecha}≠${x.esp.fecha}` : '') +
      (x.r.notas && x.r.notas.length ? `  [${x.r.notas.join(' | ')}]` : ''));
  }

  const n = filas.length;
  const cuenta = (fn) => filas.filter(fn).length;
  const pct = (k, de = n) => (de ? `${k}/${de} (${Math.round((100 * k) / de)}%)` : 'sin datos esperados');
  const peligrosos = filas.filter((x) => x.r.monto && !x.montoOk && x.r.confianza === 'alta');
  console.log('\n──────── Resumen ────────');
  console.log(`Monto bien leído:                    ${pct(cuenta((x) => x.montoOk))}`);
  console.log(`Monto leído y dado por seguro (alta): ${pct(cuenta((x) => x.r.monto && x.r.confianza === 'alta'))}`);
  console.log(`  de esos, bien:                      ${pct(cuenta((x) => x.montoOk && x.r.confianza === 'alta'))}`);
  console.log(`A revisión por duda (media/baja):    ${pct(cuenta((x) => x.r.monto && x.r.confianza !== 'alta'))}`);
  console.log(`Sin monto:                           ${pct(cuenta((x) => !x.r.monto))}`);
  console.log(`N° de operación bien:                ${pct(cuenta((x) => x.idOk === true), cuenta((x) => x.idOk !== null))}`);
  console.log(`Fecha bien:                          ${pct(cuenta((x) => x.fechaOk === true), cuenta((x) => x.fechaOk !== null))}`);
  console.log(`\nERRORES CON CONFIANZA ALTA (tiene que ser 0): ${peligrosos.length}`);
  for (const x of peligrosos) console.log(`  ✗ ${x.archivo}: leyó ${x.r.monto}, esperado ${x.esp.monto}`);
  process.exit(peligrosos.length ? 1 : 0);
}

main().catch((e) => { console.error(e); process.exit(2); });
