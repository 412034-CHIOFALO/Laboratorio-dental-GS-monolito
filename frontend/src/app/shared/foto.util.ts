/**
 * Fotos del catálogo. Una foto de celular pesa 2 a 6 MB y mide 4000 px o más: ni entra en la base de datos (se guarda
 * como texto, con un tope de 16 MB por columna) ni hace falta para mostrarla. Antes de guardarla se achica a
 * {@link FOTO_LADO_MAXIMO} px de largo y se recomprime: sale de 60 a 200 KB y se ve nítida hasta en una pantalla grande.
 * El listado del catálogo trae todas las fotos juntas, así que cada KB cuenta.
 */

/** Lado más largo de la foto guardada. */
export const FOTO_LADO_MAXIMO = 1000;

/** Calidades JPEG que se prueban, de mejor a peor, hasta que la foto pese menos que {@link FOTO_PESO_OBJETIVO}. */
export const FOTO_CALIDADES = [0.82, 0.72, 0.62, 0.5];

/** Largo máximo buscado del texto de la foto (data URL), unos 225 KB de imagen. */
export const FOTO_PESO_OBJETIVO = 300_000;

/** Lo más pesado que se acepta como foto de entrada, antes de achicarla. */
export const FOTO_ENTRADA_MAXIMA_BYTES = 30 * 1024 * 1024;

/** Medidas de la foto achicada: mantiene la proporción (horizontal, vertical o cuadrada) y nunca agranda. */
export function tamanioReducido(ancho: number, alto: number, ladoMaximo = FOTO_LADO_MAXIMO): { ancho: number; alto: number } {
  const mayor = Math.max(ancho, alto);
  if (mayor <= ladoMaximo) return { ancho, alto };
  const razon = ladoMaximo / mayor;
  return { ancho: Math.max(1, Math.round(ancho * razon)), alto: Math.max(1, Math.round(alto * razon)) };
}

/** Peso aproximado en KB de una imagen guardada como data URL (base64). */
export function kbDeDataUrl(dataUrl: string): number {
  const base64 = dataUrl.slice(dataUrl.indexOf(',') + 1);
  return Math.round((base64.length * 3) / 4 / 1024);
}

export interface FotoPreparada {
  /** data URL JPEG lista para guardar. */
  dataUrl: string;
  ancho: number;
  alto: number;
  kb: number;
}

function cargarImagen(archivo: File): Promise<HTMLImageElement> {
  const url = URL.createObjectURL(archivo);
  return new Promise((resolver, rechazar) => {
    const img = new Image();
    img.onload = () => { URL.revokeObjectURL(url); resolver(img); };
    img.onerror = () => { URL.revokeObjectURL(url); rechazar(new Error('No se pudo leer la imagen.')); };
    img.src = url;
  });
}

/**
 * Achica y recomprime una foto: respeta la orientación con la que la sacó el celular (el navegador la aplica al dibujar),
 * rellena de blanco lo transparente (un PNG con fondo transparente quedaría negro en JPEG) y no recorta nada: se guarda
 * entera, y cada pantalla decide cómo mostrarla.
 */
export async function prepararFoto(archivo: File): Promise<FotoPreparada> {
  if (!archivo.type.startsWith('image/')) throw new Error('El archivo no es una imagen.');
  if (archivo.size > FOTO_ENTRADA_MAXIMA_BYTES) throw new Error('La foto pesa demasiado (más de 30 MB).');

  const img = await cargarImagen(archivo);
  const { ancho, alto } = tamanioReducido(img.naturalWidth, img.naturalHeight);
  if (ancho < 1 || alto < 1) throw new Error('La imagen está vacía.');

  const lienzo = document.createElement('canvas');
  lienzo.width = ancho;
  lienzo.height = alto;
  const ctx = lienzo.getContext('2d');
  if (!ctx) throw new Error('Este navegador no puede procesar la imagen.');
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, ancho, alto);
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(img, 0, 0, ancho, alto);

  let dataUrl = '';
  for (const calidad of FOTO_CALIDADES) {
    dataUrl = lienzo.toDataURL('image/jpeg', calidad);
    if (dataUrl.length <= FOTO_PESO_OBJETIVO) break;
  }
  return { dataUrl, ancho, alto, kb: kbDeDataUrl(dataUrl) };
}
