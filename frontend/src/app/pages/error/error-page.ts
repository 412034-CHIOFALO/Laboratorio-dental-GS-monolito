import { Component, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../services/auth';

interface ErrorInfo {
  codigo: string;
  titulo: string;
  mensaje: string;
  icono: 'lock' | 'ban' | 'search' | 'server';
}

/**
 * Página genérica de error HTTP. El contenido se define por la `data.tipo`
 * de la ruta (forbidden / not-found / server). Reutilizable para 403/404/500.
 */
@Component({
  selector: 'app-error-page',
  standalone: true,
  templateUrl: './error-page.html',
  styleUrls: ['./error-page.css'],
})
export class ErrorPageComponent {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private auth = inject(AuthService);

  private readonly tipos: Record<string, ErrorInfo> = {
    forbidden: {
      codigo: '403',
      titulo: 'Sin permisos',
      mensaje: 'No tenés permiso para acceder a esta sección. Si creés que es un error, contactá al administrador.',
      icono: 'ban',
    },
    'not-found': {
      codigo: '404',
      titulo: 'Página no encontrada',
      mensaje: 'La página que buscás no existe o fue movida.',
      icono: 'search',
    },
    server: {
      codigo: '500',
      titulo: 'Error del servidor',
      mensaje: 'Algo salió mal de nuestro lado. Probá de nuevo en unos minutos.',
      icono: 'server',
    },
    'catalogo-deshabilitado': {
      codigo: '—',
      titulo: 'Catálogo no disponible',
      mensaje: 'El laboratorio no tiene esta sección habilitada por el momento.',
      icono: 'search',
    },
  };

  info: ErrorInfo = this.tipos[this.route.snapshot.data['tipo'] ?? 'not-found'] ?? this.tipos['not-found'];

  /** Botón principal: al dashboard si está logueado, al inicio si no. */
  irAlInicio(): void {
    this.router.navigate([this.auth.isLoggedIn() ? '/dashboard' : '/']);
  }

  volver(): void {
    history.length > 1 ? history.back() : this.irAlInicio();
  }
}
