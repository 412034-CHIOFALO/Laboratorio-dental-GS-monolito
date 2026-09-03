import { HttpErrorResponse, HttpInterceptorFn, HttpRequest, HttpHandlerFn, HttpEvent } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, Subject, catchError, switchMap, take, throwError } from 'rxjs';
import { AuthService } from '../services/auth';
import { NotificationService } from '../services/notification.service';

/**
 * Coordina los 401 concurrentes: si ya hay un refresh en curso, los demás
 * requests fallidos esperan su resultado en vez de disparar cada uno el suyo.
 * Vive a nivel de módulo (no del interceptor) porque debe compartirse entre
 * TODAS las invocaciones del interceptor, no reiniciarse en cada request.
 */
let refrescando = false;
const refrescoTerminado$ = new Subject<boolean>();

function reintentarConRefresh(
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
  auth: AuthService,
  router: Router,
  notif: NotificationService,
): Observable<HttpEvent<unknown>> {
  if (!refrescando) {
    refrescando = true;
    return auth.refrescar().pipe(
      switchMap(() => {
        refrescando = false;
        refrescoTerminado$.next(true);
        return next(req);
      }),
      catchError(() => {
        refrescando = false;
        refrescoTerminado$.next(false);
        auth.logout();
        notif.alerta('Tu sesión expiró. Iniciá sesión de nuevo.', 'Sesión finalizada');
        router.navigate(['/login']);
        return throwError(() => new Error('Sesión expirada'));
      })
    );
  }
  // Ya hay un refresh en curso — esperamos su resultado y reintentamos (o no).
  return refrescoTerminado$.pipe(
    take(1),
    switchMap(logueado => logueado ? next(req) : throwError(() => new Error('Sesión expirada')))
  );
}

/**
 * Interceptor global de errores HTTP. Maneja los casos transversales de auth
 * y conexión; el resto de errores los re-lanza para que cada componente los
 * muestre con su mensaje específico (vía NotificationService.errorHttp).
 *
 *  - 401 → intenta renovar la sesión con la cookie de refresh y reintenta el
 *    request original; si el refresh también falla, ahí sí logout + login
 *  - 403 → sin permisos: redirección a /sin-permisos
 *  - 0   → sin conexión (solo avisa si no lo maneja el componente)
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  const auth = inject(AuthService);
  const notif = inject(NotificationService);

  const esLogin = req.url.includes('/auth/login');
  const esRefresh = req.url.includes('/auth/refresh');

  return next(req).pipe(
    catchError((err: HttpErrorResponse) => {
      switch (err.status) {
        case 401:
          // Credenciales malas en el propio login, o el propio refresh ya
          // fallido — no hay nada que reintentar, cae directo al logout.
          if (esLogin) break;
          if (esRefresh) {
            auth.logout();
            notif.alerta('Tu sesión expiró. Iniciá sesión de nuevo.', 'Sesión finalizada');
            router.navigate(['/login']);
            break;
          }
          return reintentarConRefresh(req, next, auth, router, notif);

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
