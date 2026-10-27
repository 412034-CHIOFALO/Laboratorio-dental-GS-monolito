/** Lo que el servidor midió de un escaneo al recibirlo; todo puede faltar (escaneos viejos, o archivos que no son una malla). */
export interface DatosMalla {
  formato?: string | null;
  triangulos?: number | null;
  dimXMm?: number | null;
  dimYMm?: number | null;
  dimZMm?: number | null;
}

/** «STL binario · 12.340 triángulos · 62 × 48 × 31 mm»; vacío si no se sabe nada del archivo. */
export function describirMalla(m: DatosMalla): string {
  if (!m.formato) return '';
  const partes = [m.formato.replace('_', ' ').replace('BINARIO', 'binario')];
  if (m.triangulos != null) partes.push(`${m.triangulos.toLocaleString('es-AR')} triángulos`);
  if (m.dimXMm != null && m.dimYMm != null && m.dimZMm != null) {
    const mm = (n: number) => n.toLocaleString('es-AR', { maximumFractionDigits: 1 });
    partes.push(`${mm(m.dimXMm)} × ${mm(m.dimYMm)} × ${mm(m.dimZMm)} mm`);
  }
  return partes.join(' · ');
}

/** «4,2 MB», «120 KB»; vacío si no se conoce el tamaño. */
export function describirTamanio(bytes: number | null | undefined): string {
  if (bytes == null) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / 1024 / 1024).toLocaleString('es-AR', { maximumFractionDigits: 1 })} MB`;
}
