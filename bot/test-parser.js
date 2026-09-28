// Test rápido de los parsers sobre los PDFs reales
const pdfParse = require('pdf-parse/lib/pdf-parse.js');
const fs = require('fs');

function extraerMonto(texto) {
  const claves = /(monto|importe|total|transferiste|enviaste|enviaron|pagaste|recibiste|enviado|recibido)/i;
  const lineas = texto.split('\n');
  const candidatos = [];
  for (let i = 0; i < lineas.length; i++) {
    const bloque = (lineas[i] + ' ' + (lineas[i + 1] || '') + ' ' + (lineas[i + 2] || '')).trim();
    const enClave = claves.test(lineas[i]) || claves.test(lineas[Math.max(0, i - 1)] || '');
    const regex = /(\$\s*)?(\d{1,3}(?:\.\d{3})+)(?:[,\s](\d{2})\b)?/g;
    let m;
    while ((m = regex.exec(bloque)) !== null) {
      const simbolo = !!m[1];
      const entero = parseInt(m[2].replace(/\./g, ''), 10);
      if (entero < 100 || entero > 99_000_000) continue;
      candidatos.push({ valor: entero, simbolo, enClave });
    }
  }
  if (!candidatos.length) return { monto: null, confianza: 'baja' };
  const unicos = []; const vistos = new Set();
  for (const c of candidatos) {
    const k = c.valor + (c.simbolo ? 'S' : '') + (c.enClave ? 'C' : '');
    if (!vistos.has(k)) { vistos.add(k); unicos.push(c); }
  }
  unicos.sort((a, b) => (a.simbolo !== b.simbolo) ? (a.simbolo ? -1 : 1) : (a.enClave !== b.enClave) ? (a.enClave ? -1 : 1) : (b.valor - a.valor));
  const mejor = unicos[0];
  return { monto: mejor.valor, confianza: (mejor.simbolo || mejor.enClave) ? 'alta' : 'media' };
}

function extraerDePara(texto) {
  const lineas = texto.split('\n').map(l => l.trim()).filter(Boolean);
  let de = null, para = null;
  for (let i = 0; i < lineas.length; i++) {
    const l = lineas[i];
    if (/^de:?$/i.test(l) && lineas[i + 1] && !de) de = lineas[i + 1];
    if (/^para:?$/i.test(l) && lineas[i + 1] && !para) para = lineas[i + 1];
    const mOrigen = l.match(/^origen[:\s]*(.+)/i);
    const mDestino = l.match(/^destino[:\s]*(.+)/i);
    if (mOrigen && !de) de = mOrigen[1].trim();
    if (mDestino && !para) para = mDestino[1].trim();
  }
  return { de, para };
}

function extraerIdOperacion(texto) {
  const patrones = [
    /n[uú]mero\s+de\s+operaci[oó]n[^\d]*([0-9]{6,})/i,
    /(?:n[º°o]?\.?\s*de\s*)?operaci[oó]n[:\s#nro.]*([A-Z0-9-]{6,})/i,
    /c[oó]digo\s+de\s+identificaci[oó]n[:\s]*([A-Z0-9-]{6,})/i,
  ];
  for (const p of patrones) { const m = texto.match(p); if (m) return m[1].trim(); }
  return null;
}

pdfParse(fs.readFileSync('D:/DESCARGAS/ppay_operacion.pdf')).then(d => {
  const t = d.text;
  console.log('=== PERSONAL PAY ===');
  console.log('Monto:    ', JSON.stringify(extraerMonto(t)));
  console.log('De/Para:  ', JSON.stringify(extraerDePara(t)));
  console.log('N° oper.: ', extraerIdOperacion(t));
}).catch(e => console.error(e.message));
