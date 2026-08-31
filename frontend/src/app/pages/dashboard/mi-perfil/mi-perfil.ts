import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService, PerfilResponse } from '../../../services/auth';
import { NotificationService } from '../../../services/notification.service';

@Component({
  selector: 'app-mi-perfil',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './mi-perfil.html',
  styleUrls: ['./mi-perfil.css'],
})
export class MiPerfilComponent implements OnInit {
  private auth = inject(AuthService);
  private notif = inject(NotificationService);

  cargando = signal(true);
  perfil = signal<PerfilResponse | null>(null);

  form = { nombre: '', apellido: '', telefono: '' };
  guardando = signal(false);

  pass = { actual: '', nueva: '', repetir: '' };
  mostrarPass = { actual: false, nueva: false, repetir: false };
  cambiandoPass = signal(false);

  ngOnInit(): void {
    this.auth.miPerfil().subscribe({
      next: p => {
        this.perfil.set(p);
        this.form = { nombre: p.nombre ?? '', apellido: p.apellido ?? '', telefono: p.telefono ?? '' };
        this.cargando.set(false);
      },
      error: () => { this.cargando.set(false); this.notif.alerta('No se pudo cargar tu perfil'); },
    });
  }

  get rolLabel(): string {
    const r = this.perfil()?.rol ?? '';
    const m: Record<string, string> = {
      ROLE_ADMIN: 'Administrador', ROLE_TECNICO: 'Técnico',
      ROLE_ADMINISTRATIVO: 'Administrativo', ROLE_ODONTOLOGO: 'Odontólogo',
    };
    return m[r] ?? r.replace('ROLE_', '');
  }

  get iniciales(): string {
    const p = this.perfil();
    if (!p) return '';
    const n = (p.nombre?.[0] ?? p.username?.[0] ?? '').toUpperCase();
    const a = (p.apellido?.[0] ?? '').toUpperCase();
    return (n + a) || n;
  }

  /** Deja solo números y símbolos de teléfono (+ - ( ) espacio). */
  sanitizarTelefono(v: string): string { return (v || '').replace(/[^0-9+()\-\s]/g, '').slice(0, 30); }

  guardarPerfil(): void {
    if (!this.form.nombre.trim()) { this.notif.alerta('El nombre no puede quedar vacío'); return; }
    const tel = this.form.telefono?.trim();
    if (tel && !/^[0-9+()\-\s]{6,30}$/.test(tel)) {
      this.notif.alerta('El teléfono solo puede tener números y los símbolos + - ( ).', 'Teléfono inválido');
      return;
    }
    this.guardando.set(true);
    this.auth.editarPerfil({
      nombre: this.form.nombre.trim(),
      apellido: this.form.apellido.trim(),
      telefono: this.form.telefono.trim() || null,
    }).subscribe({
      next: p => { this.perfil.set(p); this.guardando.set(false); this.notif.exito('Perfil actualizado'); },
      error: e => { this.guardando.set(false); this.notif.errorHttp(e, 'No se pudo actualizar el perfil'); },
    });
  }

  get passValido(): boolean {
    return this.pass.actual.length >= 6
        && this.pass.nueva.length >= 6
        && this.pass.nueva === this.pass.repetir;
  }

  cambiarPassword(): void {
    if (this.pass.nueva !== this.pass.repetir) { this.notif.alerta('Las contraseñas nuevas no coinciden'); return; }
    if (!this.passValido) { this.notif.alerta('La contraseña debe tener al menos 6 caracteres'); return; }
    this.cambiandoPass.set(true);
    this.auth.cambiarPassword(this.pass.actual, this.pass.nueva).subscribe({
      next: r => {
        this.cambiandoPass.set(false);
        this.pass = { actual: '', nueva: '', repetir: '' };
        this.notif.exito(r.mensaje ?? 'Contraseña actualizada');
      },
      error: e => { this.cambiandoPass.set(false); this.notif.errorHttp(e, 'No se pudo cambiar la contraseña'); },
    });
  }
}
