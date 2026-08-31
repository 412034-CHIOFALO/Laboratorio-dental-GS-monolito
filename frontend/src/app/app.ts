import { Component, inject, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { SwUpdate } from '@angular/service-worker';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class App {
  protected readonly title = signal('frontend-app');

  private swUpdate = inject(SwUpdate);

  constructor() {
    if (this.swUpdate.isEnabled) {
      // Si el service worker queda en un estado irrecuperable (caché corrupta,
      // versión a medio actualizar), sin esto la app queda en pantalla en blanco
      // hasta que el usuario borre el caché a mano. Forzamos una recarga limpia.
      this.swUpdate.unrecoverable.subscribe(() => {
        window.location.reload();
      });
      // Nueva versión detectada tras un deploy: activarla y recargar, en vez de
      // dejar al usuario navegando con archivos viejos que ya no matchean.
      this.swUpdate.versionUpdates.subscribe(evt => {
        if (evt.type === 'VERSION_READY') {
          this.swUpdate.activateUpdate().then(() => window.location.reload());
        }
      });
    }
  }
}
