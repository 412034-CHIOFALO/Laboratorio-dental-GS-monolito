import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth';

export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isLoggedIn()) {
    return true;
  }

  router.navigate(['/login']);
  return false;
};

/** Como authGuard, pero además exige haber aceptado los términos y condiciones. */
export const termsGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (!auth.isLoggedIn()) {
    router.navigate(['/login']);
    return false;
  }
  if (!auth.terminosAceptados()) {
    router.navigate(['/terminos']);
    return false;
  }
  return true;
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
