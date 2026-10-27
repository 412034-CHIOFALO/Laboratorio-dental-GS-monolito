const MONEDA = new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 0 });

/** «$ 57.500». */
export function dinero(n: number | null | undefined): string {
  return n == null ? '—' : MONEDA.format(n);
}

/** 0,823 → «82 %». */
export function porcentaje(fraccion: number | null | undefined): string {
  return fraccion == null ? '—' : `${Math.round(fraccion * 100)} %`;
}

/** «26/10/2026». */
export function fecha(f: Date | null | undefined): string {
  return f ? f.toLocaleDateString('es-AR', { day: '2-digit', month: '2-digit', year: 'numeric' }) : '—';
}

/** «3 días», «1 día», «hoy». */
export function cantidadDeDias(n: number | null | undefined): string {
  if (n == null) return '—';
  if (n === 0) return 'hoy';
  return `${n.toLocaleString('es-AR', { maximumFractionDigits: 1 })} ${n === 1 ? 'día' : 'días'}`;
}

export function pluralizar(n: number, singular: string, plural: string): string {
  return `${n} ${n === 1 ? singular : plural}`;
}
