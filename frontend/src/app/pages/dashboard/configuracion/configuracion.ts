import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { CatalogoService } from '../../../services/catalogo.service';
import { NotificationService } from '../../../services/notification.service';
import { AuthService } from '../../../services/auth';
import { PageHeaderComponent } from '../../../shared/page-header';
import { SectionTabsComponent, SeccionTab } from '../../../shared/section-tabs';
import { MiPerfilComponent } from '../mi-perfil/mi-perfil';

type Seccion = 'perfil' | 'catalogo';

/**
 * Lo que cada persona puede ajustar. «Mi perfil» (datos y contraseña) es de todos; «Catálogo público» es solo de ADMIN:
 * para los demás roles la pantalla muestra únicamente su perfil y no pide nada que el servidor les negaría.
 */
@Component({
  selector: 'app-configuracion',
  imports: [PageHeaderComponent, SectionTabsComponent, MiPerfilComponent],
  templateUrl: './configuracion.html',
  styleUrls: ['./configuracion.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConfiguracionComponent implements OnInit {
  private readonly catalogo = inject(CatalogoService);
  private readonly notif = inject(NotificationService);
  private readonly auth = inject(AuthService);
  private readonly ruta = inject(ActivatedRoute);

  readonly esAdmin = this.auth.isAdmin();
  readonly secciones: SeccionTab[] = [
    { id: 'perfil', etiqueta: 'Mi perfil' },
    ...(this.esAdmin ? [{ id: 'catalogo', etiqueta: 'Catálogo público' }] : []),
  ];
  readonly subtitulos: Record<Seccion, string> = {
    perfil: 'Tus datos y tu contraseña.',
    catalogo: 'Qué puede ver cualquiera, sin iniciar sesión, de lo que ofrece el laboratorio.',
  };

  readonly seccion = signal<Seccion>('perfil');
  readonly cargando = signal(true);
  readonly guardando = signal(false);
  readonly catalogoPublicoHabilitado = signal(false);

  ngOnInit(): void {
    const pedida = this.ruta.snapshot.queryParamMap.get('seccion');
    if (pedida === 'catalogo' && this.esAdmin) this.seccion.set('catalogo');
    if (!this.esAdmin) return;
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

  elegir(id: string): void {
    if (id === 'perfil' || (id === 'catalogo' && this.esAdmin)) this.seccion.set(id);
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
