// Decide qué tan confiable es el monto leído de un comprobante cruzando varias lecturas. Puro (sin OCR ni red):
// test/lecturaMonto.test.js.
//
// Idea: un solo OCR puede equivocarse en un dígito ("65.000" por "85.000") sin que nada lo delate. Si leemos la
// imagen más de una vez, de maneras distintas, y las lecturas no coinciden, lo único honesto es no dar el monto por
// bueno: baja a "media" y el backend (RevisionPago) lo manda a revisión de una persona.
//
// Las lecturas cruzadas controlan los DÍGITOS, no que ese número sea el monto: que un número sea "el monto" lo
// dice el "$" o la palabra clave (extraerMonto). Por eso acá la confianza solo baja, nunca sube.
'use strict';

const { corregirConfusiones } = require('./parsers');

const RANGO = { baja: 0, media: 1, alta: 2 };
/** Por debajo de este puntaje (0-100) Tesseract mismo duda de los dígitos que leyó. */
const UMBRAL_DIGITOS = 70;

// A mano: el 'es-AR' de Intl no agrupa los miles de 4 cifras (5000 -> "5000") y acá se compara texto.
const conMiles = (n) => String(Math.round(Number(n))).replace(/\B(?=(\d{3})+(?!\d))/g, '.');
const pesos = (n) => `$${conMiles(n)}`;
const bajarA = (confianza, tope) => (RANGO[confianza] > RANGO[tope] ? tope : confianza);
const soloDigitos = (t) => String(t || '').replace(/\D/g, '');

/**
 * De las palabras que devolvió Tesseract ({text, confidence, bbox}), la que forma el monto dado.
 * Acepta que traiga los centavos pegados ("120.000,00") o el "$" pegado ("$85.000").
 */
function palabraDelMonto(palabras, monto) {
  const objetivo = String(monto);
  for (const w of palabras || []) {
    if (!w || !w.text || !w.bbox) continue;
    const d = soloDigitos(corregirConfusiones(w.text));
    if (d === objetivo || d === `${objetivo}00`) return w;
  }
  return null;
}

/**
 * Monto de un recorte releído solo con dígitos ("85.000", "$85.000", "85000", "85.000,00"), o null.
 * Con un recorte tan chico no hay N° de operación ni CBU con que confundirse, así que se acepta sin "$".
 */
function montoDeRecorte(texto) {
  const t = corregirConfusiones(String(texto || '').replace(/\s+/g, ''));
  const m = t.match(/(\d{1,3}(?:\.\d{3})+|\d{3,8})(?:,\d{1,2})?(?![.,]?\d)/);
  if (!m) return null;
  const v = parseInt(m[1].replace(/\./g, ''), 10);
  return v >= 100 && v <= 99_000_000 ? v : null;
}

/** La lectura que se toma como base: la de mayor confianza; a igualdad, la primera (la imagen original). */
function elegirBase(lecturas) {
  const con = (lecturas || []).filter((l) => l && l.monto);
  if (!con.length) return null;
  return con.reduce((mejor, l) => (RANGO[l.confianza] > RANGO[mejor.confianza] ? l : mejor));
}

/**
 * @param {object} p
 * @param {Array<{monto:number|null, confianza:'alta'|'media'|'baja', confianzaOcr:number|null}>} p.lecturas
 *        una por imagen leída (original, preparada...). confianzaOcr = puntaje de Tesseract (0-100) de la palabra del monto.
 * @param {{monto:number|null}|null} [p.recorte] el monto releído solo con dígitos (monto null = no se pudo leer)
 * @returns {{monto:number|null, confianza:'alta'|'media'|'baja', notas:string[]}}
 */
function decidirMonto({ lecturas, recorte = null }) {
  const base = elegirBase(lecturas);
  if (!base) return { monto: null, confianza: 'baja', notas: [] };

  let confianza = base.confianza;
  const notas = [];

  // Otra lectura, igual de segura, que dio otro monto: no sabemos cuál es la buena.
  const otros = [...new Set((lecturas || [])
    .filter((l) => l && l.monto && l.monto !== base.monto && RANGO[l.confianza] >= RANGO[base.confianza])
    .map((l) => l.monto))];
  if (otros.length) {
    confianza = bajarA(confianza, 'media');
    notas.push(`las lecturas de la imagen no coinciden (${[base.monto, ...otros].map(pesos).join(' vs ')})`);
  }

  if (base.confianzaOcr != null && base.confianzaOcr < UMBRAL_DIGITOS) {
    confianza = bajarA(confianza, 'media');
    notas.push(`el OCR dudó de los dígitos (${base.confianzaOcr}% de seguridad)`);
  }

  if (recorte && recorte.monto && recorte.monto !== base.monto) {
    confianza = bajarA(confianza, 'media');
    notas.push(`al releer solo el monto salió ${pesos(recorte.monto)} y no ${pesos(base.monto)}`);
  }

  return { monto: base.monto, confianza, notas };
}

/**
 * Entre varias lecturas de un mismo dato (N° de operación, fecha): si todas las que lo encontraron coinciden,
 * ese es el valor; si alguna dice otra cosa, se descarta el dato (null) y el pago pasa a revisión por "no se pudo leer".
 */
function consensuar(valores, nombre) {
  const v = (valores || []).filter(Boolean);
  if (!v.length) return { valor: null, notas: [] };
  const distintos = [...new Set(v)];
  if (distintos.length === 1) return { valor: distintos[0], notas: [] };
  return { valor: null, notas: [`${nombre}: las lecturas no coinciden (${distintos.join(' vs ')})`] };
}

/**
 * Contrasta el monto leído con el que la persona escribió en el pie del mensaje. Si difieren, uno de los dos
 * está mal y lo tiene que ver una persona.
 */
function contrastarConPie(lectura, montoPie) {
  if (!lectura || !lectura.monto || !montoPie || lectura.monto === montoPie) {
    return { confianza: lectura ? lectura.confianza : 'baja', nota: null };
  }
  return {
    confianza: bajarA(lectura.confianza, 'media'),
    nota: `el pie del mensaje dice ${pesos(montoPie)} y el comprobante ${pesos(lectura.monto)}`,
  };
}

/**
 * Un monto que propone la IA solo se da por seguro si el texto que leyeron las reglas lo confirma: la IA propone y
 * las reglas confirman, así una alucinación no se registra sola.
 */
function confianzaDeIA(montoIA, texto, local) {
  if (local && local.monto && local.monto !== montoIA) {
    return { confianza: 'media', nota: `la IA leyó ${pesos(montoIA)} y las reglas ${pesos(local.monto)}` };
  }
  const normalizado = corregirConfusiones(String(texto || ''));
  // con bordes: "5000" no está en un CBU o en un N° de operación que lo contenga
  const aparece = [conMiles(montoIA), String(montoIA)].some((forma) =>
    new RegExp(`(?<![\\d.,])${forma.replace(/\./g, '\\.')}(?!\\d)`).test(normalizado));
  return aparece
    ? { confianza: 'alta', nota: null }
    : { confianza: 'media', nota: `${pesos(montoIA)} lo leyó la IA y el texto del comprobante no lo confirma` };
}

module.exports = {
  UMBRAL_DIGITOS, palabraDelMonto, montoDeRecorte, elegirBase, decidirMonto, consensuar, contrastarConPie, confianzaDeIA,
};
