import { Component, inject } from '@angular/core';
import { Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../services/auth';
import { NotificationService } from '../../services/notification.service';

/**
 * Gate de aceptación de términos y condiciones. Se muestra una única vez,
 * antes de entrar al dashboard, a todo usuario que aún no los haya aceptado
 * (lo controla termsGuard). El ADMIN que crea la cuenta no acepta por él.
 */
@Component({
  selector: 'app-terminos',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './terminos.html',
  styleUrls: ['./terminos.css'],
})
export class TerminosComponent {
  private auth = inject(AuthService);
  private router = inject(Router);
  private notif = inject(NotificationService);

  leido = false;
  guardando = false;

  aceptar(): void {
    if (!this.leido || this.guardando) return;
    this.guardando = true;
    this.auth.aceptarTerminos().subscribe({
      next: () => {
        this.auth.saveTerminosAceptados(true);
        this.notif.exito('Gracias por aceptar los términos', 'Listo');
        this.router.navigate(['/dashboard']);
      },
      error: () => {
        this.guardando = false;
        this.notif.error('No se pudo registrar la aceptación. Probá de nuevo.', 'Error');
      }
    });
  }

  cerrarSesion(): void {
    this.auth.logout();
    this.router.navigate(['/login']);
  }
}
