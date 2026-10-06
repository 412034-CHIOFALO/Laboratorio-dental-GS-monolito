// OCR local de comprobantes (Tesseract). Aparte de index.js para poder probarlo (test/ocr.test.js).
//
// Por qué no alcanza con createWorker('spa') a secas: tesseract.js arranca en PSM 6 ("un solo
// bloque uniforme de texto"), y con ese modo IGNORA las líneas en letra grande — justo el monto
// de las capturas de Mercado Pago/Personal Pay ("$ 85.000" en 64 px). Resultado: el monto sale
// vacío y hay que pedirlo a mano. Medido con test/fixtures: PSM 6 pierde el monto en 2 de 4
// comprobantes; PSM 3 (automático) y PSM 11 (texto disperso) lo leen en los 4, y ninguno inventa
// un monto sobre una imagen que no es un comprobante.
'use strict';

const path = require('path');
const { createWorker } = require('tesseract.js');
const { extraerMonto } = require('./parsers');

const PSM_AUTO = '3';
const PSM_DISPERSO = '11';

/**
 * @param {object} [opciones]
 * @param {string} [opciones.cachePath] carpeta con spa.traineddata (si no está, tesseract la baja)
 * @returns {Promise<{ leerTexto(buffer: Buffer): Promise<string>, cerrar(): Promise<void> }>}
 */
async function crearOcr(opciones = {}) {
  const cachePath = opciones.cachePath || path.resolve('.');
  const worker = await createWorker('spa', 1, { cachePath, logger: () => {} });
  // dpi fijo: evita el ruido "Estimating resolution as ..." de cada imagen
  await worker.setParameters({ tessedit_pageseg_mode: PSM_AUTO, user_defined_dpi: '300' });

  // El worker es uno solo y setParameters cambia su estado: dos lecturas a la vez se pisarían.
  let cola = Promise.resolve();
  const serializado = (fn) => {
    const r = cola.then(fn, fn);
    cola = r.catch(() => {});
    return r;
  };

  async function leer(buffer, psm) {
    await worker.setParameters({ tessedit_pageseg_mode: psm });
    return (await worker.recognize(buffer)).data.text || '';
  }

  return {
    /** Texto de la imagen. Segunda pasada solo si la primera no encontró un monto confiable. */
    leerTexto(buffer) {
      return serializado(async () => {
        let texto = await leer(buffer, PSM_AUTO);
        if (extraerMonto(texto).confianza !== 'alta') {
          const otro = await leer(buffer, PSM_DISPERSO);
          // se suman los dos textos: los extractores leen línea por línea y toman lo mejor de cada uno
          if (otro.trim()) texto = `${texto}\n${otro}`;
        }
        await worker.setParameters({ tessedit_pageseg_mode: PSM_AUTO });
        return texto;
      });
    },
    async cerrar() {
      await worker.terminate();
    },
  };
}

module.exports = { crearOcr };
