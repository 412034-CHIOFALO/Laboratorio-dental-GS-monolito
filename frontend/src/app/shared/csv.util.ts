export type CeldaCsv = string | number | null | undefined;

/**
 * Arma el texto de un CSV que Excel en castellano abre bien: separado por «;» (la coma ya es el decimal), con decimales
 * con coma y saltos de línea de Windows. Los textos que empiezan como una fórmula (=, +, -, @) se marcan con una comilla:
 * el nombre de un odontólogo o un concepto vienen de personas y de mails, y Excel ejecuta lo que parece una fórmula.
 */
export function aCsv(columnas: string[], filas: CeldaCsv[][]): string {
  const lineas = [columnas, ...filas].map(fila => fila.map(celda).join(';'));
  return lineas.join('\r\n') + '\r\n';
}

function celda(valor: CeldaCsv): string {
  if (valor == null) return '';
  if (typeof valor === 'number') return Number.isFinite(valor) ? String(valor).replace('.', ',') : '';
  const texto = /^[=+\-@\t\r]/.test(valor) ? `'${valor}` : valor;
  return /[;"\r\n]/.test(texto) ? `"${texto.replace(/"/g, '""')}"` : texto;
}

/** Baja el CSV al disco. El BOM del principio hace que Excel respete las tildes. */
export function descargarCsv(nombreArchivo: string, columnas: string[], filas: CeldaCsv[][]): void {
  const blob = new Blob(['﻿' + aCsv(columnas, filas)], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const enlace = document.createElement('a');
  enlace.href = url;
  enlace.download = nombreArchivo.endsWith('.csv') ? nombreArchivo : `${nombreArchivo}.csv`;
  enlace.click();
  setTimeout(() => URL.revokeObjectURL(url), 15_000);
}
