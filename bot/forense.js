// Señales de que un comprobante pudo haber sido editado. Puro (sin red ni WhatsApp): test/forense.test.js.
//
// Ojo con qué es esto: NO prueba que un comprobante sea verdadero ni falso (eso solo lo sabe el banco del
// receptor). Son indicios baratos que, si aparecen, mandan el pago a revisión de una persona en vez de
// registrarlo solo. Una captura de pantalla de un comprobante editado a mano en el celular no deja rastro;
// por eso esto es una capa más, no la solución (la solución es que el receptor confirme el pago).
'use strict';

// Programas con los que NO sale un comprobante bancario / de billetera legítimo.
const EDITORES = /(photoshop|illustrator|canva|gimp|pixlr|snapseed|picsart|lightroom|affinity|paint\.net|corel|inkscape|photopea|fotor|befunky|figma|ilovepdf|smallpdf|sejda|pdfescape|pdf24|foxit|nitro|wondershare|pdfelement|acrobat pro|microsoft word|libreoffice)/i;

/** Busca el nombre de un editor en los metadatos del archivo (cabecera y final). */
function detectarEditor(buffer) {
  if (!buffer || !buffer.length) return null;
  const inicio = buffer.subarray(0, 262144).toString('latin1');
  const final = buffer.length > 262144 ? buffer.subarray(-16384).toString('latin1') : '';
  const m = (inicio + '\n' + final).match(EDITORES);
  return m ? m[1] : null;
}

/** "D:20260912143200-03'00'" (formato de fecha de un PDF) -> milisegundos, o null. */
function fechaPdf(valor) {
  const m = /^D?:?(\d{4})(\d{2})(\d{2})(\d{2})?(\d{2})?(\d{2})?/.exec(String(valor || ''));
  if (!m) return null;
  return Date.UTC(+m[1], +m[2] - 1, +m[3], +(m[4] || 0), +(m[5] || 0), +(m[6] || 0));
}

/**
 * @param {Buffer} buffer contenido del archivo
 * @param {string} mime
 * @param {{Producer?: string, Creator?: string, CreationDate?: string, ModDate?: string}} [pdfInfo] metadatos del PDF (pdf-parse)
 * @returns {string[]} señales encontradas (vacío = nada raro)
 */
function senalesDeEdicion(buffer, mime, pdfInfo) {
  const senales = [];
  const editor = detectarEditor(buffer);
  if (editor) senales.push(`el archivo se generó o editó con ${editor}`);

  if (pdfInfo) {
    const creado = fechaPdf(pdfInfo.CreationDate);
    const modificado = fechaPdf(pdfInfo.ModDate);
    if (creado && modificado && modificado - creado > 120 * 1000) {
      senales.push('el PDF se modificó después de generarse');
    }
    const programa = `${pdfInfo.Producer || ''} ${pdfInfo.Creator || ''}`;
    const m = EDITORES.exec(programa);
    if (m && !senales.some(s => s.includes(m[1].toLowerCase()))) senales.push(`el PDF lo produjo ${m[1]}`);
  }
  return senales;
}

module.exports = { senalesDeEdicion, detectarEditor, fechaPdf };
