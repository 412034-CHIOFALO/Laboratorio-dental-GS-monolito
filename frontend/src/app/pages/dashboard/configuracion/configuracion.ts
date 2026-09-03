import { Component, OnInit, inject, signal } from '@angular/core';
import { CatalogoService } from '../../../services/catalogo.service';
import { NotificationService } from '../../../services/notification.service';

/** ADMIN-only (ver dashboard.ts, entrada de nav con roles: ['ROLE_ADMIN']). */
@Component({
  selector: 'app-configuracion',
  standalone: true,
  templateUrl: './configuracion.html',
  styleUrls: ['./configuracion.css'],
})
export class ConfiguracionComponent implements OnInit {
  private readonly catalogo = inject(CatalogoService);
  private readonly notif = inject(NotificationService);

  readonly cargando = signal(true);
  readonly guardando = signal(false);
  readonly catalogoPublicoHabilitado = signal(false);

  ngOnInit(): void {
    this.catalogo.obtenerConfiguracionPublica().subscribe({
      next: resp => {
        this.catalogoPublicoHabilitado.set(resp.habilitado);
        this.cargando.set(false);
      },
      error: () => {
        this.notif.error('No se pudo cargar la configuración del catálogo público.');
        this.cargando.set(false);
      },
    });
  }

  toggleCatalogoPublico(): void {
    if (this.guardando()) return;
    const nuevoValor = !this.catalogoPublicoHabilitado();
    this.guardando.set(true);
    this.catalogo.actualizarConfiguracionPublica(nuevoValor).subscribe({
      next: resp => {
        this.catalogoPublicoHabilitado.set(resp.habilitado);
        this.guardando.set(false);
        this.notif.exito(resp.habilitado
          ? 'El catálogo público ya está visible en /catalogo.'
          : 'El catálogo público quedó oculto.');
      },
      error: () => {
        this.guardando.set(false);
        this.notif.error('No se pudo actualizar la configuración.');
      },
    });
  }
}
