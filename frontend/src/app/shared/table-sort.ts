/**
 * Helper de ordenamiento reutilizable para tablas.
 *
 * En vez de repetir en cada componente el estado de orden (campo + dirección),
 * el comparador y los íconos de flecha, se instancia uno de estos por tabla:
 *
 *   sort = new TableSort<Pedido>('fechaEntrega', 'desc');
 *   get filas() { return this.sort.aplicar(this.pedidos); }
 *
 * En el template:
 *   <th class="sortable" (click)="sort.toggle('nroPedido')">
 *     N° <span class="sort-ico">{{ sort.icono('nroPedido') }}</span>
 *   </th>
 *   @for (p of filas; track p.id) { ... }
 *
 * Para columnas calculadas (que no son una propiedad directa), se pasa un
 * accessor: sort.aplicar(items, (item, campo) => campo === 'saldo' ? calc(item) : (item as any)[campo]).
 */
export type SortDir = 'asc' | 'desc';

export class TableSort<T> {
  campo: string | null;
  dir: SortDir;

  constructor(campoInicial: string | null = null, dirInicial: SortDir = 'asc') {
    this.campo = campoInicial;
    this.dir = dirInicial;
  }

  /** Click en un header: si es la misma columna alterna asc/desc; si es otra, la ordena asc. */
  toggle(campo: string): void {
    if (this.campo === campo) {
      this.dir = this.dir === 'asc' ? 'desc' : 'asc';
    } else {
      this.campo = campo;
      this.dir = 'asc';
    }
  }

  /** Ícono para el header: flecha llena en la columna activa, doble flecha tenue en las demás. */
  icono(campo: string): string {
    if (this.campo !== campo) return '⇅';
    return this.dir === 'asc' ? '▲' : '▼';
  }

  /**
   * Devuelve una COPIA ordenada (no muta el array original). Si no hay columna
   * activa, devuelve el array tal cual. Detecta números, fechas (Date o string
   * ISO/parseable) y texto (comparación local, case-insensitive). Nulos al final.
   */
  aplicar(items: T[], accessor?: (item: T, campo: string) => unknown): T[] {
    if (!this.campo) return items;
    const campo = this.campo;
    const factor = this.dir === 'asc' ? 1 : -1;
    const get = accessor ?? ((item: T, c: string) => (item as Record<string, unknown>)[c]);

    return [...items].sort((a, b) => {
      const va = get(a, campo);
      const vb = get(b, campo);

      // Nulos/undefined siempre al final, sin importar la dirección.
      const na = va == null || va === '';
      const nb = vb == null || vb === '';
      if (na && nb) return 0;
      if (na) return 1;
      if (nb) return -1;

      return this.comparar(va, vb) * factor;
    });
  }

  private comparar(a: unknown, b: unknown): number {
    // Números
    if (typeof a === 'number' && typeof b === 'number') return a - b;

    // Fechas (Date o string parseable a fecha)
    const da = this.aFecha(a);
    const db = this.aFecha(b);
    if (da !== null && db !== null) return da - db;

    // Texto (local, case-insensitive, tolera acentos)
    return String(a).localeCompare(String(b), 'es', { sensitivity: 'base', numeric: true });
  }

  /** Convierte a timestamp si el valor parece una fecha; si no, null. */
  private aFecha(v: unknown): number | null {
    if (v instanceof Date) return v.getTime();
    if (typeof v === 'string' && /^\d{4}-\d{2}-\d{2}/.test(v)) {
      const t = Date.parse(v);
      return isNaN(t) ? null : t;
    }
    return null;
  }
}
