// Normaliza teléfonos argentinos al JID de WhatsApp. Puro, sin red (test/telefono.test.js).
//
// Los celulares argentinos en WhatsApp son 54 + 9 + código de área + número (10 dígitos nacionales:
// área de 2-4 dígitos + abonado de 6-8). Cómo los escribe la gente: "+54 9 351 658-8576",
// "0351 15 6588576", "351-6588576", "15 6588576" (con el área implícita), "54351...", etc. Antes el
// bot nunca agregaba el 9 ni quitaba el 15: casi todo número escrito "a la argentina" quedaba mal
// formado, y mandar a un número que no existe en WhatsApp es de lo que más pesa para un ban.
'use strict';

const LARGO_NACIONAL = 10;

/**
 * @param {string} entrada teléfono como lo escribió una persona
 * @param {string} [areaPorDefecto] código de área para números locales sin área ("6588576", "15 6588576")
 * @returns {{ jid: string, numero: string } | null} null si no se puede interpretar
 */
function normalizarTelefonoAR(entrada, areaPorDefecto = '351') {
  let d = String(entrada ?? '').replace(/\D/g, '');
  if (!d) return null;

  d = d.replace(/^00/, '');                       // 0054...
  if (d.startsWith('54') && d.length >= 12) {     // con código de país
    d = d.slice(2);
    if (d.startsWith('9') && d.length === LARGO_NACIONAL + 1) d = d.slice(1);   // ya traía el 9
  }
  if (d.startsWith('0')) d = d.slice(1);          // el 0 de discado nacional

  // "15" de celular después del área: 351 15 6588576 -> 351 6588576
  if (d.length === LARGO_NACIONAL + 2) {
    for (const largoArea of [2, 3, 4]) {
      if (d.slice(largoArea, largoArea + 2) === '15') {
        d = d.slice(0, largoArea) + d.slice(largoArea + 2);
        break;
      }
    }
  }

  // número local sin área: abonado de 10 - largo(area) dígitos, con o sin el 15 delante
  const faltan = LARGO_NACIONAL - areaPorDefecto.length;
  if (d.length === faltan) d = areaPorDefecto + d;
  else if (d.length === faltan + 2 && d.startsWith('15')) d = areaPorDefecto + d.slice(2);

  if (d.length !== LARGO_NACIONAL || !/^[1-9]/.test(d)) return null;
  if (/(\d)\1{6,}/.test(d)) return null;       // 0000000, 1111111...: no es un teléfono real
  return { jid: `549${d}@c.us`, numero: `549${d}` };
}

module.exports = { normalizarTelefonoAR };
