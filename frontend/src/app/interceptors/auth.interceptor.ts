import { HttpInterceptorFn } from '@angular/common/http';

/**
 * Adjunta el JWT (Authorization: Bearer) a TODAS las llamadas salientes,
 * salvo el login (que va sin token a propósito).
 *
 * Sin este interceptor las peticiones llegan al gateway sin credencial,
 * el gateway responde 401 y el errorInterceptor expulsa al usuario apenas
 * inicia sesión (el clásico "la sesión dura 1 segundo").
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const token = localStorage.getItem('gs_token');
  const esLogin = req.url.includes('/auth/login');

  if (token && !esLogin && !req.headers.has('Authorization')) {
    req = req.clone({
      setHeaders: { Authorization: `Bearer ${token}` },
    });
  }

  return next(req);
};
