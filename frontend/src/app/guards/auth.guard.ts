import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { AuthService } from '../services/auth';

/**
 * La pista local de expiración puede decir "vencido" (access token de 12hs)
 * mientras la cookie de refresh (30 días) todavía sirve — típico: alguien
 * reabre una pestaña vieja al otro día. Antes de mandar al login de una,
 * probamos renovar en silencio.
 */
function siguesLogueadoTrasRefrescar(auth: AuthService, router: Router) {
  return auth.refrescar().pipe(
    map(() => true),
    catchError(() => {
      router.navigate(['/login']);
      return of(false);
    })
  );
}

export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isLoggedIn()) {
    return true;
  }

  return siguesLogueadoTrasRefrescar(auth, router);
};

function tieneTerminosAceptados(auth: AuthService, router: Router): boolean {
  if (auth.terminosAceptados()) return true;
  router.navigate(['/terminos']);
  return false;
}

/** Como authGuard, pero además exige haber aceptado los términos y condiciones. */
export const termsGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isLoggedIn()) {
    return tieneTerminosAceptados(auth, router);
  }

  return siguesLogueadoTrasRefrescar(auth, router).pipe(
    map(logueado => logueado && tieneTerminosAceptados(auth, router))
  );
};

/**
 * Bloquea cualquier ruta hija de /dashboard salvo mi-perfil cuando la cuenta
 * tiene una contraseña temporal pendiente de cambio (reseteada por un ADMIN).
 * Se usa como canActivateChild en la ruta padre — corre en cada navegación
 * DENTRO del dashboard, a diferencia de canActivate que solo corre una vez al entrar.
 */
export const forcePasswordChangeGuard: CanActivateFn = (route) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.debeCambiarPassword() && route.routeConfig?.path !== 'mi-perfil') {
    router.navigate(['/dashboard/mi-perfil'], { queryParams: { obligatorio: '1' } });
    return false;
  }
  return true;
};
