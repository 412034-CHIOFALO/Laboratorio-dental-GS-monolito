import { Component, OnInit, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../services/auth';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { NotificationService } from '../../services/notification.service';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-login',
  templateUrl: './login.html',
  styleUrls: ['./login.css'],
  imports: [FormsModule, RouterLink]
})
export class LoginComponent implements OnInit {
  username = '';
  password = '';
  mostrarPassword = false;
  errorMessage = '';
  loginLoading = false;

  readonly mostrarDemoHint = !environment.production;

  /** true si la app corre instalada como PWA (no en una pestaña del navegador). */
  readonly esPwa = typeof window !== 'undefined' && (
    window.matchMedia('(display-mode: standalone)').matches ||
    window.matchMedia('(display-mode: fullscreen)').matches ||
    window.matchMedia('(display-mode: minimal-ui)').matches ||
    (window.navigator as any).standalone === true   // iOS Safari
  );

  private notif = inject(NotificationService);

  constructor(private authService: AuthService, private router: Router) {}

  /** El ícono de la PWA abre directo en /login — si ya hay sesión, saltamos a destino. */
  ngOnInit(): void {
    if (this.authService.isLoggedIn()) {
      if (!this.authService.terminosAceptados()) {
        this.router.navigate(['/terminos']);
      } else if (this.authService.debeCambiarPassword()) {
        this.router.navigate(['/dashboard/mi-perfil'], { queryParams: { obligatorio: '1' } });
      } else {
        this.router.navigate(['/dashboard']);
      }
    }
  }

  /** Botón del hint demo — autocompleta los campos */
  usarCreds(user: string, pass: string): void {
    this.username = user;
    this.password = pass;
    this.errorMessage = '';
  }

  onSubmit() {
    this.errorMessage = '';
    this.loginLoading = true;

    // Validación rápida client-side antes de pegarle al back
    if (!this.username.trim() || !this.password) {
      this.loginLoading = false;
      this.notif.alerta('Completá usuario y contraseña');
      return;
    }

    this.authService.login(this.username, this.password).subscribe({
      next: (response) => {
        this.authService.saveToken(response.access_token);
        this.authService.saveTerminosAceptados(response.terminosAceptados);
        this.authService.saveDebeCambiarPassword(response.debeCambiarPassword);
        this.notif.exito(`Bienvenido ${this.username}`, 'Sesión iniciada');
        if (!response.terminosAceptados) {
          this.router.navigate(['/terminos']);
        } else if (response.debeCambiarPassword) {
          this.router.navigate(['/dashboard/mi-perfil'], { queryParams: { obligatorio: '1' } });
        } else {
          this.router.navigate(['/dashboard']);
        }
      },
      error: (err: HttpErrorResponse) => {
        this.loginLoading = false;
        const msg = this.mensajeError(err, 'Usuario o contraseña incorrectos');
        this.errorMessage = msg; // mantener la versión inline para accesibilidad
        // status 0 = sin conexión al back. Mensaje específico, no genérico.
        if (err.status === 0) {
          this.notif.error('No se pudo conectar al servidor. Verificá que el backend esté corriendo.', 'Sin conexión');
        } else if (err.status === 401) {
          this.notif.error(msg, 'Acceso denegado');
        } else if (err.status === 403) {
          this.notif.alerta(msg, 'Cuenta no activada');
        } else {
          this.notif.errorHttp(err, 'No se pudo iniciar sesión');
        }
      }
    });
  }

  /**
   * Extrae el mejor mensaje de un error HTTP del backend para mostrarlo inline.
   * Prioriza el detalle de validación por campo, luego el mensaje de negocio.
   * El campo 'error' del ErrorResponse es solo la frase HTTP genérica (ej:
   * "Bad Request") — nunca hay que mostrarlo como si fuera el mensaje real.
   */
  private mensajeError(err: any, fallback: string): string {
    const body = err?.error ?? {};
    if (Array.isArray(body.campos) && body.campos.length > 0) {
      return body.campos.map((c: any) => c.mensaje).join(' · ');
    }
    return body.mensaje ?? fallback;
  }
}
