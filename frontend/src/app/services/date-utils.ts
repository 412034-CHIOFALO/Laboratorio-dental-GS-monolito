/**
 * Helpers para trabajar con fechas LocalDate (YYYY-MM-DD, sin hora) del backend
 * sin caer en el desfasaje de un día que produce el motor de JS al mezclar
 * UTC y horario local (Argentina es UTC-3).
 *
 * - Al LEER un LocalDate: `new Date('2026-07-08')` lo interpreta como
 *   medianoche UTC, que en Argentina cae en el día anterior a las 21hs.
 *   Solución: parsear con hora local explícita → `fechaLocal(str)`.
 * - Al ESCRIBIR "hoy" como LocalDate: `new Date().toISOString()` convierte
 *   a UTC antes de formatear, así que cerca de la medianoche local puede
 *   devolver el día siguiente. Solución: formatear con getters locales →
 *   `hoyComoLocalDate()`.
 */

/** Parsea un LocalDate ('YYYY-MM-DD') como medianoche LOCAL, no UTC. */
export function fechaLocal(localDate: string): Date {
  return new Date(localDate + 'T00:00:00');
}

/** Formatea una Date como LocalDate ('YYYY-MM-DD') usando su fecha LOCAL (no UTC). */
export function comoLocalDate(d: Date): string {
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${mm}-${dd}`;
}

/** Fecha de hoy en el timezone del usuario, formateada como LocalDate ('YYYY-MM-DD'). */
export function hoyComoLocalDate(): string {
  return comoLocalDate(new Date());
}
