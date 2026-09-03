import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { CatalogoService } from '../services/catalogo.service';

/**
 * Solo deja entrar a /catalogo si el ADMIN habilitó el catálogo público (ver
 * ConfiguracionCatalogoPublico en el backend). Si está apagado, o si el
 * chequeo falla por cualquier motivo, redirige a la página de error
 * reusada (ver error-page.ts, tipo 'catalogo-deshabilitado') en vez de
 * dejar entrar a una página vacía.
 */
export const catalogoPublicoGuard: CanActivateFn = () => {
  const catalogo = inject(CatalogoService);
  const router = inject(Router);

  return catalogo.catalogoPublicoHabilitado().pipe(
    map(resp => {
      if (resp.habilitado) return true;
      router.navigate(['/catalogo-no-disponible']);
      return false;
    }),
    catchError(() => {
      router.navigate(['/catalogo-no-disponible']);
      return of(false);
    })
  );
};
