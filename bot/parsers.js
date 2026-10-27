// Parsers y validaciones PURAS del bot (sin WhatsApp ni red): texto del comprobante -> monto,
// N° de operación, De/Para; el pie "Emisor (Receptor)"; el efectivo; y la validación cruzada.
// Viven acá, y no en index.js, para poder probarlas con node --test (index.js arranca el
// cliente de WhatsApp apenas se importa). Ver test/parsers.test.js.
'use strict';

// ─── Parsers ─────────────────────────────────────────────────────────────────

/**
 * Detecta una declaración de efectivo en el grupo.
 * Formato: "efectivo 50000 (Receptor)" o "efectivo $50.000 (Receptor)"
 * Devuelve { monto, receptor } o null si no coincide.
 */
function parsearEfectivo(texto) {
  if (!texto) return null;
  const m = texto.trim().match(/^efectivo\s+\$?\s*([\d.,]+)\s*\(([^)]+)\)/i);
  if (!m) return null;
  const montoStr = m[1].replace(/\./g, '').replace(',', '.');
  const monto = parseFloat(montoStr);
  if (!monto || monto < 100) return null;
  return { monto: Math.round(monto), receptor: m[2].trim() };
}

/**
 * Pie: "EMISOR (RECEPTOR)" → { emisor, receptor, montoManual }.
 * El monto es OPCIONAL: si lo ponés (antes o después del paréntesis), se usa
 * como respaldo cuando el comprobante es una foto que el OCR no pudo leer.
 *   "Dr García (Carlos López)"        → sin monto (se lee del comprobante)
 *   "Dr García (Carlos López) 10000"  → con monto manual
 */
function parsearPie(texto) {
  const r = { emisor: null, receptor: null, montoManual: null };
  if (!texto) return r;
  const m = texto.match(/^(.*?)\(([^)]+)\)(.*)$/s);
  if (!m) return r;
  r.emisor = m[1].trim() || null;
  r.receptor = m[2].trim();

  // Monto opcional: buscar un número en lo que rodea al paréntesis
  const resto = `${m[1]} ${m[3]}`;
  const mm = resto.match(/\$?\s*(\d{1,3}(?:\.\d{3})+(?:,\d{2})?|\d{3,}(?:,\d{2})?)/);
  if (mm) {
    const v = Math.round(parseFloat(mm[1].replace(/\./g, '').replace(',', '.')));
    if (v >= 100) r.montoManual = v;
  }
  return r;
}

/**
 * Arregla las confusiones típicas del OCR DENTRO de lo que tiene forma de monto, sin tocar el resto del texto:
 * letras por dígitos ("85.OOO", "l.250.000") y la coma como separador de miles ("85,000" -> "85.000").
 * Un monto con centavos ("120.000,00") queda igual.
 */
function corregirConfusiones(texto) {
  if (!texto) return '';
  return texto
    .replace(/(?<![\p{L}\d.,])[\dOoIl]{1,3}(?:[.,][\dOoIl]{3})+(?![\p{L}\d])/gu, (t) =>
      ((t.match(/\d/g) || []).length < 2 ? t : t.replace(/[Oo]/g, '0').replace(/[Il]/g, '1')))
    .replace(/(?<![\d.,])(\d{1,3})((?:,\d{3})+)(?![\d,]|\.\d)/g, (_, a, b) => a + b.replace(/,/g, '.'));
}

/**
 * Monto: solo números con formato de dinero ($60.000 / $60.000,00). Descarta IDs.
 * Maneja el caso de Personal Pay donde el monto viene partido en líneas
 * ("$" / "60.000" / "00") uniendo el texto antes de buscar.
 * Un monto sin separador de miles ("$ 85000", "$ 85 000", "$500") solo vale si trae el "$" pegado: sin él,
 * cualquier número suelto (un CBU, un N° de operación) podría tomarse por monto.
 */
function extraerMonto(texto) {
  texto = corregirConfusiones(texto);
  const claves = /(monto|importe|total|transferiste|enviaste|enviaron|pagaste|recibiste|enviado|recibido)/i;

  // Unimos líneas para tolerar montos partidos: "$\n60.000\n00" → "$ 60.000 00"
  // y también el caso normal. Trabajamos sobre el texto "aplanado" por bloques.
  const lineas = texto.split('\n');
  const candidatos = [];

  for (let i = 0; i < lineas.length; i++) {
    // Bloque: la línea + las 2 siguientes (por si el monto está partido)
    const bloque = (lineas[i] + ' ' + (lineas[i + 1] || '') + ' ' + (lineas[i + 2] || '')).trim();
    const enClave = claves.test(lineas[i]) || claves.test(lineas[Math.max(0, i - 1)] || '');

    // $ opcional, miles obligatorio (60.000), centavos opcionales separados o con coma
    const regex = /(\$\s*)?(\d{1,3}(?:\.\d{3})+)(?:[,\s](\d{2})\b)?/g;
    let m;
    while ((m = regex.exec(bloque)) !== null) {
      const simbolo = !!m[1];
      const entero = parseInt(m[2].replace(/\./g, ''), 10);
      if (entero < 100 || entero > 99_000_000) continue;
      candidatos.push({ valor: entero, simbolo, enClave });
    }
    const sinMiles = /\$\s*(\d{3,8}|\d{1,3}(?: \d{3})+)(?:,\d{2})?(?![.,]?\d)/g;
    while ((m = sinMiles.exec(bloque)) !== null) {
      const entero = parseInt(m[1].replace(/ /g, ''), 10);
      if (entero < 100 || entero > 99_000_000) continue;
      candidatos.push({ valor: entero, simbolo: true, enClave });
    }
  }

  if (!candidatos.length) return { monto: null, confianza: 'baja' };

  // Dedup por valor (el bloque solapa líneas y puede repetir)
  const unicos = [];
  const vistos = new Set();
  for (const c of candidatos) {
    const k = c.valor + (c.simbolo ? 'S' : '') + (c.enClave ? 'C' : '');
    if (!vistos.has(k)) { vistos.add(k); unicos.push(c); }
  }

  unicos.sort((a, b) =>
    (a.simbolo !== b.simbolo) ? (a.simbolo ? -1 : 1)
    : (a.enClave !== b.enClave) ? (a.enClave ? -1 : 1)
    : (b.valor - a.valor));
  const mejor = unicos[0];
  return { monto: mejor.valor, confianza: (mejor.simbolo || mejor.enClave) ? 'alta' : 'media' };
}

/** N° de operación / código del comprobante. */
function extraerIdOperacion(texto) {
  const patrones = [
    /n[uú]mero\s+de\s+operaci[oó]n[^\d]*([0-9]{6,})/i,
    /(?:n[º°o]?\.?\s*de\s*)?operaci[oó]n[:\s#nro.]*([A-Z0-9-]{6,})/i,
    /c[oó]digo\s+de\s+identificaci[oó]n[:\s]*([A-Z0-9-]{6,})/i,
    /(?:n[º°o]?\.?\s*)?transacci[oó]n[:\s#nro.]*([A-Z0-9-]{6,})/i,
  ];
  for (const p of patrones) {
    const m = texto.match(p);
    if (m) return m[1].trim();
  }
  return null;
}

/**
 * Extrae emisor y receptor del comprobante. Soporta muchas billeteras
 * reconociendo varias etiquetas, en tres formas:
 *   - etiqueta sola, nombre en la línea siguiente  (Mercado Pago: "De"\n"Nombre")
 *   - etiqueta + nombre en la misma línea          ("Origen: Juan")
 *   - etiqueta pegada al nombre                     (Personal Pay: "OrigenJuan")
 */
const ETIQ_EMISOR   = ['de', 'origen', 'remitente', 'ordenante', 'enviado por', 'titular origen'];
const ETIQ_RECEPTOR = ['para', 'destino', 'destinatario', 'beneficiario', 'enviado a', 'acreditado en', 'titular destino'];

function extraerDePara(texto) {
  const lineas = texto.split('\n').map(l => l.trim()).filter(Boolean);
  let de = null, para = null;
  for (let i = 0; i < lineas.length; i++) {
    if (!de)   { const v = matchEtiqueta(lineas[i], lineas[i + 1], ETIQ_EMISOR);   if (v) de = v; }
    if (!para) { const v = matchEtiqueta(lineas[i], lineas[i + 1], ETIQ_RECEPTOR); if (v) para = v; }
  }
  return { de, para };
}

/** Intenta extraer el nombre que sigue a alguna de las etiquetas dadas. */
function matchEtiqueta(linea, siguiente, etiquetas) {
  const low = linea.toLowerCase();
  for (const e of etiquetas) {
    // etiqueta sola en su línea → nombre en la siguiente (MP)
    if (low === e || low === e + ':') return (siguiente || '').trim() || null;
    // etiqueta + nombre en la misma línea, con separador
    if (low.startsWith(e + ' ') || low.startsWith(e + ':')) {
      return linea.slice(e.length).replace(/^[:\s]+/, '').trim() || null;
    }
    // etiqueta pegada al nombre (Personal Pay), solo etiquetas de una palabra,
    // y validando que lo que sigue empiece con mayúscula (un nombre real)
    if (!e.includes(' ') && low.startsWith(e) && linea.length > e.length) {
      const resto = linea.slice(e.length);
      if (/^[A-ZÁÉÍÓÚÑ]/.test(resto)) return resto.trim();
    }
  }
  return null;
}

/**
 * Compara dos nombres de forma flexible. Tolera:
 *  - acentos (normaliza)
 *  - palabras partidas por mala extracción de PDF ("Nicolás" → "Nicol S")
 *  - prefijos ("nicol" ↔ "nicolas")
 *  - títulos (Dr, Dra) que se ignoran
 *
 * Coincide si comparten una palabra (exacta o por prefijo de 4+ letras), o si
 * los nombres completos son muy similares (tolerancia a 1-2 caracteres).
 */
function nombresCoinciden(a, b) {
  if (!a || !b) return { ok: false, comun: null };
  const norm = s => s.toLowerCase()
    .normalize('NFD').replace(/[̀-ͯ]/g, '')          // quitar acentos
    .replace(/[^a-z\s]/g, ' ').replace(/\s+/g, ' ').trim();
  const TITULOS = new Set(['dr', 'dra', 'dro', 'sr', 'sra', 'lic']);

  const na = norm(a), nb = norm(b);
  const pa = na.split(' ').filter(w => w.length >= 3 && !TITULOS.has(w));
  const pb = nb.split(' ').filter(w => w.length >= 3 && !TITULOS.has(w));

  // 1) Palabra exacta o por prefijo (4+ letras) → tolera "nicol" vs "nicolas"
  for (const wa of pa) {
    for (const wb of pb) {
      if (wa === wb) return { ok: true, comun: wa };
      if (wa.length >= 4 && wb.length >= 4 && (wa.startsWith(wb) || wb.startsWith(wa))) {
        return { ok: true, comun: wa.length <= wb.length ? wa : wb };
      }
    }
  }

  // 2) Nombres completos sin espacios, muy similares (Levenshtein ≤ 20%)
  //    Cubre "nicolas" vs "nicol s" → "nicolas" vs "nicols" (distancia 1)
  const ca = na.replace(/\s/g, ''), cb = nb.replace(/\s/g, '');
  if (ca.length >= 5 && cb.length >= 5) {
    const d = distanciaLevenshtein(ca, cb);
    if (d / Math.max(ca.length, cb.length) <= 0.2) return { ok: true, comun: '~similar' };
  }

  return { ok: false, comun: null };
}

/** Distancia de edición (cuántos cambios para pasar de a a b). */
function distanciaLevenshtein(a, b) {
  const m = a.length, n = b.length;
  const dp = Array.from({ length: m + 1 }, (_, i) => {
    const row = new Array(n + 1).fill(0);
    row[0] = i;
    return row;
  });
  for (let j = 0; j <= n; j++) dp[0][j] = j;
  for (let i = 1; i <= m; i++) {
    for (let j = 1; j <= n; j++) {
      dp[i][j] = Math.min(
        dp[i - 1][j] + 1,
        dp[i][j - 1] + 1,
        dp[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1)
      );
    }
  }
  return dp[m][n];
}

/**
 * Valida el pie contra el comprobante. Compara:
 *   - emisor del pie  vs  "De"/"Origen" del comprobante
 *   - receptor del pie vs  "Para"/"Destino" del comprobante
 *
 * Pasa SOLO si TODOS los campos presentes en el comprobante coinciden.
 * Si el comprobante tiene De y Para, ambos deben coincidir (no alcanza con uno).
 * Así se detecta si alguien pone un emisor o receptor falso.
 */
function validarPersonas(pie, lectura) {
  const checks = [];
  if (lectura.de)   checks.push({ campo: 'emisor',   pieVal: pie.emisor,   compVal: lectura.de,   ...nombresCoinciden(pie.emisor, lectura.de) });
  if (lectura.para) checks.push({ campo: 'receptor', pieVal: pie.receptor, compVal: lectura.para, ...nombresCoinciden(pie.receptor, lectura.para) });

  if (!checks.length) return { ok: true, detalle: 'comprobante sin datos para validar' };

  const fallidos = checks.filter(c => !c.ok);
  if (fallidos.length === 0) {
    const det = checks.map(c => `${c.campo} por "${c.comun}"`).join(', ');
    return { ok: true, detalle: `coinciden ${det}` };
  }

  // Al menos un campo NO coincide → la validación falla
  const det = fallidos.map(c => `${c.campo} "${c.pieVal}" ≠ comprobante "${c.compVal}"`).join('; ');
  return { ok: false, detalle: det };
}

/**
 * Fecha del comprobante (primera fecha válida del texto), en formato AAAA-MM-DD, o null.
 * Reconoce "12 de septiembre de 2026" (Mercado Pago), "15/09/2026", "15-09-26", "15.09.2026" y "2026-09-15".
 * Un día imposible (el OCR lee "42 de septiembre") se descarta en vez de adivinarlo: sin fecha, el pago
 * pasa a revisión humana, que es lo correcto.
 */
const MESES = { enero: 1, febrero: 2, marzo: 3, abril: 4, mayo: 5, junio: 6, julio: 7, agosto: 8,
  septiembre: 9, setiembre: 9, octubre: 10, noviembre: 11, diciembre: 12 };

function fechaValida(d, m, a) {
  if (a < 100) a += 2000;
  if (a < 2020 || a > 2100 || m < 1 || m > 12 || d < 1) return null;
  const f = new Date(Date.UTC(a, m - 1, d));
  if (f.getUTCFullYear() !== a || f.getUTCMonth() !== m - 1 || f.getUTCDate() !== d) return null;
  return `${a}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
}

function extraerFecha(texto) {
  if (!texto) return null;
  const candidatos = [];
  for (const m of texto.matchAll(/(\d{1,2})\s+de\s+([a-záéíóú]+)\s+(?:de\s+)?(\d{4})/gi)) {
    const mes = MESES[m[2].toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '')];
    if (mes) candidatos.push({ i: m.index, f: fechaValida(+m[1], mes, +m[3]) });
  }
  for (const m of texto.matchAll(/(\d{1,2})[\/\-.](\d{1,2})[\/\-.](\d{4}|\d{2})(?!\d)/g)) {
    candidatos.push({ i: m.index, f: fechaValida(+m[1], +m[2], +m[3]) });
  }
  for (const m of texto.matchAll(/(\d{4})-(\d{2})-(\d{2})/g)) {
    candidatos.push({ i: m.index, f: fechaValida(+m[3], +m[2], +m[1]) });
  }
  const validos = candidatos.filter(c => c.f).sort((a, b) => a.i - b.i);
  return validos.length ? validos[0].f : null;
}

module.exports = {
  parsearEfectivo, parsearPie, extraerMonto, corregirConfusiones, extraerIdOperacion, extraerDePara, extraerFecha,
  nombresCoinciden, validarPersonas, ETIQ_EMISOR, ETIQ_RECEPTOR,
};
