import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../services/auth';
import { NotificationService } from '../services/notification.service';

/**
 * Interceptor global de errores HTTP. Maneja los casos transversales de auth
 * y conexión; el resto de errores los re-lanza para que cada componente los
 * muestre con su mensaje específico (vía NotificationService.errorHttp).
 *
 *  - 401 → sesión expirada: logout + redirección al login
 *  - 403 → sin permisos: redirección a /sin-permisos
 *  - 0   → sin conexión (solo avisa si no lo maneja el componente)
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  const auth = inject(AuthService);
  const notif = inject(NotificationService);

  return next(req).pipe(
    catchError((err: HttpErrorResponse) => {
      const esLogin = req.url.includes('/auth/login');

      switch (err.status) {
        case 401:
          // Token vencido o inválido — no aplica al propio login (credenciales malas)
          if (!esLogin) {
            auth.logout();
            notif.alerta('Tu sesión expiró. Iniciá sesión de nuevo.', 'Sesión finalizada');
            router.navigate(['/login']);
          }
          break;

        case 403:
          notif.error('No tenés permiso para esa acción.', 'Sin permisos');
          router.navigate(['/sin-permisos']);
          break;

        case 0:
          // Sin conexión al backend (servidor caído / red). Aviso suave.
          // Los componentes que ya manejan status 0 con errorHttp mostrarán su propio
          // mensaje; este es el respaldo para requests que nadie maneja.
          break;
      }

      // Siempre re-lanzamos para que el componente decida si muestra algo más
      return throwError(() => err);
    })
  );
};
