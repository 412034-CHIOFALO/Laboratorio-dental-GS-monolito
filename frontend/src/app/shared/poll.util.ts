import { DestroyRef } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { interval } from 'rxjs';

/** Intervalo estándar de refresco en segundo plano para pantallas colaborativas. */
export const POLL_MS = 6000;

/**
 * Refresca datos en segundo plano cada `ms` mientras el componente esté vivo,
 * sin necesidad de un Subject + ngOnDestroy manual en cada pantalla.
 *
 * Uso: `iniciarPolling(() => this.cargar(true), this.destroyRef);` — el
 * método de carga debe soportar un flag "silencioso" para no mostrar el
 * spinner ni pisar mensajes de error en cada refresco de fondo.
 */
export function iniciarPolling(fn: () => void, destroyRef: DestroyRef, ms = POLL_MS): void {
  interval(ms).pipe(takeUntilDestroyed(destroyRef)).subscribe(fn);
}
