import { Component, OnInit, OnDestroy } from '@angular/core';
import { NgClass } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Subject, interval } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { AuthService } from '../../../services/auth';
import { NotificationService } from '../../../services/notification.service';
import { environment } from '../../../../environments/environment';
import { MOCK_USUARIOS, MockUsuario, clonar } from '../../../services/mock-data';
import { TableSort } from '../../../shared/table-sort';

/** Cada cuánto se refresca la lista en segundo plano (ms). Ver nota en ngOnInit. */
const POLL_MS = 6000;

@Component({
  selector: 'app-usuarios',
  standalone: true,
  imports: [NgClass, FormsModule],
  templateUrl: './usuarios.html',
  styleUrls: ['./usuarios.css']
})
export class UsuariosComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();
  private gatewayUrl = environment.gatewayUrl;

  usuarios: MockUsuario[] = [];
  loading = false;
  error   = '';

  readonly sort = new TableSort<MockUsuario>();
  /** Lista ordenada para la tabla (no muta this.usuarios). */
  get usuariosVista(): MockUsuario[] {
    return this.sort.aplicar(this.usuarios, (u, c) => {
      const x = u as unknown as Record<string, unknown>;
      if (c === 'nombre')  return `${x['nombre'] ?? ''} ${x['apellido'] ?? ''}`.trim();
      if (c === 'estado')  return x['enabled'] ? 1 : 0;
      return x[c];
    });
  }

  // ── Modal crear usuario ──────────────────────────────────────
  showModal  = false;
  saving     = false;
  saveError  = '';
  saveSuccess = '';

  form = {
    nombre: '', apellido: '', username: '', password: '',
    rol: '' as 'TECNICO' | 'ADMINISTRATIVO' | 'ODONTOLOGO' | 'ADMIN' | ''
  };

  // ── Modal editar teléfono ────────────────────────────────────
  showTelModal  = false;
  editandoUsuario: MockUsuario | null = null;
  formTel       = { telefono: '' };
  savingTel     = false;
  telError      = '';
  telSuccess    = '';

  // ── Modal resetear contraseña ────────────────────────────────
  showResetModal    = false;
  resetUsuario: MockUsuario | null = null;
  reseteando        = false;
  resetError        = '';
  passwordTemporal  = '';

  // Mock store
  private mockStore: MockUsuario[] = clonar(MOCK_USUARIOS);
  private nextMockId = 100;

  constructor(
    private http: HttpClient,
    private authService: AuthService,
    private notif: NotificationService,
  ) {}

  /** Crear un usuario nuevo es exclusivo de ADMIN. */
  get puedeCrear(): boolean {
    return this.authService.isAdmin();
  }

  /** Dar de alta (activar) una cuenta pendiente es exclusivo de ADMINISTRATIVO — a
   * propósito no puede ser el mismo ADMIN que la creó (separación de poderes). */
  get puedeActivar(): boolean {
    return this.authService.isAdministrativo();
  }

  /** Resetear la contraseña de otro integrante también es exclusivo de ADMINISTRATIVO. */
  get puedeResetearPassword(): boolean {
    return this.authService.isAdministrativo();
  }

  ngOnInit() {
    this.cargarUsuarios();
    // Refresco silencioso en segundo plano: con dos pestañas abiertas (ej. un
    // ADMIN crea, un ADMINISTRATIVO activa), sin esto cada una queda mostrando
    // datos viejos hasta que alguien recarga a mano.
    interval(POLL_MS).pipe(takeUntil(this.destroy$)).subscribe(() => this.cargarUsuarios(true));
  }

  ngOnDestroy() {
    this.destroy$.next();
    this.destroy$.complete();
  }

  private headers(): HttpHeaders {
    return new HttpHeaders({ Authorization: `Bearer ${this.authService.getToken()}` });
  }

  /** @param silencioso true en los refrescos de fondo: no muestra el spinner ni pisa errores previos. */
  cargarUsuarios(silencioso = false) {
    if (!silencioso) { this.loading = true; this.error = ''; }

    if (environment.useMocks) {
      setTimeout(() => { this.usuarios = clonar(this.mockStore); if (!silencioso) this.loading = false; }, 200);
      return;
    }

    this.http.get<MockUsuario[]>(`${this.gatewayUrl}/api/auth/usuarios`, { headers: this.headers() })
      .subscribe({
        next: (data) => { this.usuarios = data; if (!silencioso) this.loading = false; },
        error: () => { if (!silencioso) { this.error = 'No se pudo cargar la lista de usuarios.'; this.loading = false; } }
      });
  }

  // ── Crear ────────────────────────────────────────────────────

  abrirModal() {
    if (!this.puedeCrear) {
      this.notif.alerta('Crear usuarios es exclusivo de un Administrador.', 'Sin permisos');
      return;
    }
    this.form = { nombre: '', apellido: '', username: '', password: '', rol: '' };
    this.saveError = ''; this.saveSuccess = '';
    this.showModal = true;
  }

  cerrarModal() { this.showModal = false; }

  crearUsuario() {
    if (!this.form.rol) { this.saveError = 'Seleccioná un rol.'; return; }
    this.saving = true; this.saveError = '';

    if (environment.useMocks) {
      setTimeout(() => {
        if (this.mockStore.some(u => u.username === this.form.username)) {
          this.saving = false; this.saveError = 'El nombre de usuario ya está en uso.'; return;
        }
        this.mockStore.push({
          id: this.nextMockId++, username: this.form.username,
          nombre: this.form.nombre, apellido: this.form.apellido,
          rol: this.form.rol as string, enabled: false
        });
        this.saving = false; this.saveSuccess = 'Usuario creado correctamente.';
        setTimeout(() => { this.cerrarModal(); this.cargarUsuarios(); }, 1000);
      }, 300);
      return;
    }

    this.http.post(`${this.gatewayUrl}/api/auth/register`, this.form, { headers: this.headers() })
      .subscribe({
        next: () => {
          this.saving = false; this.saveSuccess = 'Usuario creado correctamente.';
          setTimeout(() => { this.cerrarModal(); this.cargarUsuarios(); }, 1200);
        },
        error: (err) => { this.saving = false; this.saveError = this.mensajeError(err, 'Error al crear el usuario.'); }
      });
  }

  // ── Activar / Desactivar ─────────────────────────────────────

  activar(id: number) {
    if (!this.puedeActivar) {
      this.notif.alerta('Dar de alta un usuario requiere rol Administrativo — no puede ser el mismo Admin que lo creó.', 'Sin permisos');
      return;
    }
    this.cambiarEstado(id, true);
  }

  desactivar(id: number) {
    this.cambiarEstado(id, false);
  }

  private cambiarEstado(id: number, activo: boolean) {
    if (environment.useMocks) {
      const u = this.mockStore.find(x => x.id === id);
      if (u) u.enabled = activo;
      this.cargarUsuarios();
      return;
    }
    this.http.patch(`${this.gatewayUrl}/api/auth/usuarios/${id}/estado`,
      { activo }, { headers: this.headers() })
      .subscribe({ next: () => this.cargarUsuarios(), error: () => {} });
  }

  // ── Editar teléfono ──────────────────────────────────────────

  abrirEditarTel(u: MockUsuario) {
    this.editandoUsuario = u;
    this.formTel         = { telefono: u.telefono ?? '' };
    this.telError        = ''; this.telSuccess = '';
    this.showTelModal    = true;
  }

  cerrarTelModal() { this.showTelModal = false; this.editandoUsuario = null; }

  /** Deja solo números y símbolos de teléfono (+ - ( ) espacio). */
  sanitizarTelefono(v: string): string { return (v || '').replace(/[^0-9+()\-\s]/g, '').slice(0, 30); }

  /**
   * Extrae el mejor mensaje de un error HTTP del backend para mostrarlo inline.
   * Prioriza el detalle de validación por campo, luego el mensaje de negocio.
   */
  private mensajeError(err: any, fallback: string): string {
    const body = err?.error ?? {};
    if (Array.isArray(body.campos) && body.campos.length > 0) {
      return body.campos.map((c: any) => c.mensaje).join(' · ');
    }
    return body.mensaje ?? body.error ?? fallback;
  }

  guardarTelefono() {
    if (!this.editandoUsuario) return;
    const tel = this.formTel.telefono?.trim();
    if (tel && !/^[0-9+()\-\s]{6,30}$/.test(tel)) {
      this.telError = 'El teléfono solo puede tener números y los símbolos + - ( ).';
      return;
    }
    this.savingTel = true; this.telError = '';
    const id = this.editandoUsuario.id;

    if (environment.useMocks) {
      setTimeout(() => {
        const u = this.mockStore.find(x => x.id === id);
        if (u) u.telefono = this.formTel.telefono;
        this.savingTel = false; this.telSuccess = 'Teléfono actualizado.';
        setTimeout(() => { this.cerrarTelModal(); this.cargarUsuarios(); }, 1000);
      }, 300);
      return;
    }

    this.http.patch(`${this.gatewayUrl}/api/auth/usuarios/${id}/telefono`,
      { telefono: this.formTel.telefono }, { headers: this.headers() })
      .subscribe({
        next: () => {
          this.savingTel = false; this.telSuccess = 'Teléfono actualizado correctamente.';
          setTimeout(() => { this.cerrarTelModal(); this.cargarUsuarios(); }, 1000);
        },
        error: (err) => { this.savingTel = false; this.telError = this.mensajeError(err, 'Error al actualizar el teléfono.'); }
      });
  }

  // ── Resetear contraseña ──────────────────────────────────────

  abrirResetModal(u: MockUsuario) {
    if (!this.puedeResetearPassword) {
      this.notif.alerta('Resetear una contraseña requiere rol Administrativo.', 'Sin permisos');
      return;
    }
    this.resetUsuario = u;
    this.resetError = '';
    this.passwordTemporal = '';
    this.showResetModal = true;
  }

  cerrarResetModal() {
    this.showResetModal = false;
    this.resetUsuario = null;
    this.passwordTemporal = '';
  }

  confirmarReset() {
    if (!this.resetUsuario) return;
    this.reseteando = true;
    this.resetError = '';
    this.authService.resetearPassword(this.resetUsuario.id).subscribe({
      next: (r) => {
        this.reseteando = false;
        this.passwordTemporal = r.passwordTemporal;
      },
      error: (err) => {
        this.reseteando = false;
        this.resetError = this.mensajeError(err, 'No se pudo resetear la contraseña.');
      }
    });
  }

  copiarPasswordTemporal() {
    if (!this.passwordTemporal) return;
    navigator.clipboard?.writeText(this.passwordTemporal);
    this.notif.exito('Contraseña temporal copiada al portapapeles');
  }

  // ── Helpers ──────────────────────────────────────────────────

  rolLabel(rol: string): string {
    const map: Record<string, string> = {
      ADMIN: 'Administrador', TECNICO: 'Técnico',
      ADMINISTRATIVO: 'Administrativo', ODONTOLOGO: 'Odontólogo'
    };
    return map[rol] ?? rol;
  }
}
