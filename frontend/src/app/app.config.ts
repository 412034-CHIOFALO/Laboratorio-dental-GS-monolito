import { ApplicationConfig, provideBrowserGlobalErrorListeners, provideZoneChangeDetection, isDevMode } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import { errorInterceptor } from './interceptors/error.interceptor';
import { provideAnimations } from '@angular/platform-browser/animations';
import { provideToastr } from 'ngx-toastr';

import { routes } from './app.routes';
import { provideServiceWorker } from '@angular/service-worker';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    // El JWT viaja en una cookie httpOnly (el browser la manda solo, no hace
    // falta adjuntar Authorization a mano). withXsrfConfiguration lee la
    // cookie XSRF-TOKEN que pone Spring Security y la reenvía como header
    // X-XSRF-TOKEN en cada request mutante — los nombres coinciden con los
    // defaults de Spring a propósito, cero config del lado del server aparte
    // de tener CSRF habilitado.
    provideHttpClient(
      withInterceptors([errorInterceptor]),
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' })
    ),
    provideAnimations(),
    provideToastr({
      positionClass: 'toast-top-right',
      timeOut: 3500,
      progressBar: true,
      progressAnimation: 'decreasing',
      closeButton: false,
      newestOnTop: true,
      preventDuplicates: true,
      tapToDismiss: true,
    }),
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:30000',
    }),
  ],
};
