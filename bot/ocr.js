// OCR local de comprobantes (Tesseract). Aparte de index.js para poder probarlo (test/ocr.test.js).
//
// Por qué no alcanza con createWorker('spa') a secas: tesseract.js arranca en PSM 6 ("un solo
// bloque uniforme de texto"), y con ese modo IGNORA las líneas en letra grande — justo el monto
// de las capturas de Mercado Pago/Personal Pay ("$ 85.000" en 64 px). Resultado: el monto sale
// vacío y hay que pedirlo a mano. Medido con test/fixtures: PSM 6 pierde el monto en 2 de 4
// comprobantes; PSM 3 (automático) y PSM 11 (texto disperso) lo leen en los 4, y ninguno inventa
// un monto sobre una imagen que no es un comprobante.
//
// Qué tan de fiar es el monto: leerImagen() no se queda con una sola lectura. Lee la imagen tal cual llegó y una
// versión preparada (imagen.js), y después vuelve a leer SOLO la zona del monto con dígitos. Si las lecturas se
// contradicen, o Tesseract mismo duda de los dígitos, el monto baja a confianza "media" y el pago va a revisión
// humana (lecturaMonto.js tiene las reglas; RevisionPago, en el backend, es quien decide qué se registra solo).
'use strict';

const path = require('path');
const { createWorker } = require('tesseract.js');
const { extraerMonto, extraerIdOperacion, extraerFecha } = require('./parsers');
const { aPng, prepararImagen, recortarMonto } = require('./imagen');
const { palabraDelMonto, montoDeRecorte, elegirBase, decidirMonto, consensuar } = require('./lecturaMonto');

const PSM_AUTO = '3';
const PSM_DISPERSO = '11';
const PSM_UNA_LINEA = '7';
const SOLO_MONTO = '0123456789.,$ ';

/**
 * @param {object} [opciones]
 * @param {string} [opciones.cachePath] carpeta con spa.traineddata (si no está, tesseract la baja)
 * @returns {Promise<{
 *   leerTexto(buffer: Buffer): Promise<string>,
 *   leerImagen(buffer: Buffer): Promise<LecturaImagen>,
 *   cerrar(): Promise<void>
 * }>}
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
    const { data } = await worker.recognize(buffer);
    return { texto: data.text || '', palabras: data.words || [] };
  }

  /** Texto y palabras de una imagen. Segunda pasada solo si la primera no encontró un monto confiable. */
  async function pasadas(buffer) {
    let { texto, palabras } = await leer(buffer, PSM_AUTO);
    if (extraerMonto(texto).confianza !== 'alta') {
      const otro = await leer(buffer, PSM_DISPERSO);
      // se suman los dos textos: los extractores leen línea por línea y toman lo mejor de cada uno
      if (otro.texto.trim()) texto = `${texto}\n${otro.texto}`;
      palabras = [...palabras, ...otro.palabras];
    }
    await worker.setParameters({ tessedit_pageseg_mode: PSM_AUTO });
    return { texto, palabras };
  }

  /** Lee un recorte de una sola línea restringido a dígitos y signos de monto. */
  async function leerLineaDeMonto(buffer) {
    try {
      await worker.setParameters({ tessedit_pageseg_mode: PSM_UNA_LINEA, tessedit_char_whitelist: SOLO_MONTO });
      return (await worker.recognize(buffer)).data.text || '';
    } finally {
      await worker.setParameters({ tessedit_pageseg_mode: PSM_AUTO, tessedit_char_whitelist: '' });
    }
  }

  return {
    /** Texto de la imagen tal cual llegó (una sola lectura). */
    leerTexto(buffer) {
      return serializado(async () => (await pasadas(buffer)).texto);
    },

    /**
     * Lectura completa y cruzada de un comprobante en imagen.
     * `notas` explica por qué algún dato quedó con poca confianza (o sin leer): va al log y al motivo de revisión.
     */
    leerImagen(buffer) {
      return serializado(async () => {
        const notas = [];
        const fuentes = [{ origen: 'original', imagen: await aPng(buffer) }];
        try {
          fuentes.push({ origen: 'preparada', imagen: (await prepararImagen(buffer)).buffer });
        } catch (e) {
          notas.push(`no se pudo preparar la imagen (${e.message}): se leyó una sola vez`);
        }

        const lecturas = [];
        for (const f of fuentes) {
          const p = await pasadas(f.imagen);
          const m = extraerMonto(p.texto);
          const palabra = m.monto ? palabraDelMonto(p.palabras, m.monto) : null;
          lecturas.push({
            origen: f.origen,
            imagen: f.imagen,
            texto: p.texto,
            monto: m.monto,
            confianza: m.confianza,
            confianzaOcr: palabra ? Math.round(palabra.confidence) : null,
            bbox: palabra ? palabra.bbox : null,
            idOperacion: extraerIdOperacion(p.texto),
            fecha: extraerFecha(p.texto),
          });
        }

        let recorte = null;
        const base = elegirBase(lecturas);
        if (base && base.bbox) {
          try {
            recorte = { monto: montoDeRecorte(await leerLineaDeMonto(await recortarMonto(base.imagen, base.bbox))) };
          } catch (e) {
            notas.push(`no se pudo releer el monto (${e.message})`);
          }
        }

        const monto = decidirMonto({ lecturas, recorte });
        const id = consensuar(lecturas.map((l) => l.idOperacion), 'N° de operación');
        const fecha = consensuar(lecturas.map((l) => l.fecha), 'fecha');
        return {
          texto: lecturas.map((l) => l.texto).join('\n'),
          monto: monto.monto,
          confianza: monto.confianza,
          idOperacion: id.valor,
          fecha: fecha.valor,
          notas: [...notas, ...monto.notas, ...id.notas, ...fecha.notas],
          lecturas: lecturas.map(({ origen, monto: m, confianza: c, confianzaOcr }) => ({ origen, monto: m, confianza: c, confianzaOcr })),
          recorte,
        };
      });
    },

    async cerrar() {
      await worker.terminate();
    },
  };
}

/**
 * @typedef {object} LecturaImagen
 * @property {string} texto texto de todas las lecturas (la original primero)
 * @property {number|null} monto
 * @property {'alta'|'media'|'baja'} confianza
 * @property {string|null} idOperacion null si no se leyó o si las lecturas se contradicen
 * @property {string|null} fecha AAAA-MM-DD, con la misma regla
 * @property {string[]} notas
 */

module.exports = { crearOcr };
