import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';
import { delay } from 'rxjs/operators';
import { environment } from '../../environments/environment';
import { MOCK_USUARIOS, clonar } from './mock-data';

export interface RegisterPayload {
  nombre: string;
  apellido: string;
  username: string;
  password: string;
  rol: 'TECNICO' | 'ADMINISTRATIVO' | 'ODONTOLOGO' | 'ADMIN';
}

export interface LoginResponse {
  rol: string;
  /** epoch millis — cuándo vence la sesión. */
  expiraEn: number;
  terminosAceptados: boolean;
  debeCambiarPassword: boolean;
}

export interface PerfilResponse {
  id: number;
  username: string;
  nombre: string;
  apellido: string;
  telefono: string | null;
  rol: string;
  enabled: boolean;
  pendienteAprobacion: boolean;
  terminosAceptados: boolean;
  debeCambiarPassword: boolean;
}

export interface PerfilUpdate {
  nombre?: string;
  apellido?: string;
  telefono?: string | null;
}

export interface UsuarioListado {
  id: number;
  username: string;
  nombre: string;
  apellido: string;
  rol: string;
  enabled: boolean;
}

/**
 * El JWT vive en una cookie httpOnly — este servicio nunca lo toca ni lo ve.
 * Lo único que se guarda acá son "pistas" NO sensibles (rol, vencimiento,
 * username) para que la UI pueda decidir qué mostrar sin pegarle al backend
 * en cada click — la autorización real siempre la hace el servidor con la
 * cookie, esto es puramente cosmético/UX.
 */
@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private gatewayUrl = environment.gatewayUrl;
  private readonly ROL_KEY = 'gs_rol';
  private readonly EXP_KEY = 'gs_exp';
  private readonly USERNAME_KEY = 'gs_username';
  private readonly TERMINOS_KEY = 'gs_terminos_aceptados';
  private readonly DEBE_CAMBIAR_KEY = 'gs_debe_cambiar_password';

  constructor(private http: HttpClient) {}

  login(username: string, password: string): Observable<LoginResponse> {
    // MODO DEMO: cualquier user/pass válido entra, ya se considera onboardeado
    if (environment.useMocks) {
      if (username && password) {
        return of({
          rol: 'ADMIN',
          expiraEn: Date.now() + 12 * 60 * 60 * 1000,
          terminosAceptados: true,
          debeCambiarPassword: false,
        }).pipe(delay(400));
      }
      return throwError(() => ({ status: 401, error: { mensaje: 'Credenciales incorrectas' } }));
    }

    // El endpoint de login viene de environment.loginUrl: en dev va por el
    // gateway (:8080) y en prod va relativo (vía nginx). Siempre /api/auth/login.
    // La cookie de sesión la pone el backend solo (header Set-Cookie) — acá
    // no hay nada que guardar del token.
    return this.http.post<LoginResponse>(environment.loginUrl, { username, password });
  }

  /** Guarda las pistas no sensibles de la sesión recién iniciada (ver comentario de la clase). */
  saveSession(username: string, resp: LoginResponse): void {
    localStorage.setItem(this.USERNAME_KEY, username);
    localStorage.setItem(this.ROL_KEY, `ROLE_${resp.rol}`);
    localStorage.setItem(this.EXP_KEY, String(resp.expiraEn));
    // Residuo de versiones previas (guardaban el JWT crudo acá) — lo sacamos
    // en cuanto alguien vuelve a loguearse, para no dejarlo pudriéndose.
    localStorage.removeItem('gs_token');
  }

  aceptarTerminos(): Observable<PerfilResponse> {
    if (environment.useMocks) {
      this.saveTerminosAceptados(true);
      return of({ ...this.mockPerfil(), terminosAceptados: true }).pipe(delay(200));
    }
    return this.http.post<PerfilResponse>(`${this.gatewayUrl}/api/auth/me/aceptar-terminos`, {});
  }

  saveTerminosAceptados(v: boolean): void {
    localStorage.setItem(this.TERMINOS_KEY, v ? '1' : '0');
  }

  terminosAceptados(): boolean {
    return localStorage.getItem(this.TERMINOS_KEY) === '1';
  }

  saveDebeCambiarPassword(v: boolean): void {
    localStorage.setItem(this.DEBE_CAMBIAR_KEY, v ? '1' : '0');
  }

  debeCambiarPassword(): boolean {
    return localStorage.getItem(this.DEBE_CAMBIAR_KEY) === '1';
  }

  /** Resetea la contraseña de otro usuario (ADMINISTRATIVO). Devuelve la temporal UNA sola vez. */
  resetearPassword(id: number): Observable<{ mensaje: string; passwordTemporal: string }> {
    if (environment.useMocks) {
      return of({ mensaje: 'Contraseña reseteada (demo).', passwordTemporal: 'demo1234X' }).pipe(delay(300));
    }
    return this.http.post<{ mensaje: string; passwordTemporal: string }>(
      `${this.gatewayUrl}/api/auth/usuarios/${id}/resetear-password`, {}
    );
  }

  listarUsuarios(): Observable<UsuarioListado[]> {
    if (environment.useMocks) {
      return of(clonar(MOCK_USUARIOS) as unknown as UsuarioListado[]).pipe(delay(200));
    }
    return this.http.get<UsuarioListado[]>(`${this.gatewayUrl}/api/auth/usuarios`);
  }

  register(payload: RegisterPayload): Observable<{ mensaje: string; username: string }> {
    if (environment.useMocks) {
      return of({ mensaje: 'Usuario creado (demo)', username: payload.username }).pipe(delay(300));
    }
    return this.http.post<{ mensaje: string; username: string }>(
      `${this.gatewayUrl}/api/auth/register`, payload
    );
  }

  aprobarUsuario(id: number): Observable<any> {
    if (environment.useMocks) {
      return of({ ok: true }).pipe(delay(200));
    }
    return this.http.put(`${this.gatewayUrl}/api/auth/usuarios/${id}/aprobar`, {});
  }

  /** Activa/desactiva un integrante (entrada/salida de personal). */
  cambiarEstadoUsuario(id: number, activo: boolean): Observable<any> {
    if (environment.useMocks) {
      return of({ id, enabled: activo }).pipe(delay(200));
    }
    return this.http.patch(`${this.gatewayUrl}/api/auth/usuarios/${id}/estado`, { activo });
  }

  /** Actualiza el teléfono del integrante (lo usa el bot para identificarlo). */
  actualizarTelefonoUsuario(id: number, telefono: string): Observable<any> {
    if (environment.useMocks) {
      return of({ id, telefono }).pipe(delay(200));
    }
    return this.http.patch(`${this.gatewayUrl}/api/auth/usuarios/${id}/telefono`, { telefono });
  }

  // ── Perfil propio (self-service) ──────────────────────────────

  private _mockPerfil?: PerfilResponse;

  private mockPerfil(): PerfilResponse {
    if (!this._mockPerfil) {
      const u = this.getUsername() || 'admin';
      this._mockPerfil = {
        id: 1, username: u,
        nombre: u === 'admin' ? 'Rebeca' : u,
        apellido: u === 'admin' ? 'González' : '',
        telefono: '3516588576',
        rol: this.getRoles()[0] || 'ROLE_ADMIN',
        enabled: true, pendienteAprobacion: false, terminosAceptados: true, debeCambiarPassword: false,
      };
    }
    return this._mockPerfil;
  }

  miPerfil(): Observable<PerfilResponse> {
    if (environment.useMocks) return of({ ...this.mockPerfil() }).pipe(delay(150));
    return this.http.get<PerfilResponse>(`${this.gatewayUrl}/api/auth/me`);
  }

  editarPerfil(req: PerfilUpdate): Observable<PerfilResponse> {
    if (environment.useMocks) {
      this._mockPerfil = { ...this.mockPerfil(), ...req } as PerfilResponse;
      return of({ ...this._mockPerfil }).pipe(delay(250));
    }
    return this.http.patch<PerfilResponse>(`${this.gatewayUrl}/api/auth/me`, req);
  }

  cambiarPassword(actual: string, nueva: string): Observable<{ mensaje: string }> {
    if (environment.useMocks) {
      if (!actual || actual.length < 6) {
        return throwError(() => ({ status: 400, error: { error: 'La contraseña actual no es correcta.' } }));
      }
      return of({ mensaje: 'Contraseña actualizada correctamente.' }).pipe(delay(250));
    }
    return this.http.post<{ mensaje: string }>(`${this.gatewayUrl}/api/auth/me/password`, { actual, nueva });
  }

  isLoggedIn(): boolean {
    const exp = Number(localStorage.getItem(this.EXP_KEY));
    return !!exp && exp > Date.now();
  }

  getUsername(): string {
    return localStorage.getItem(this.USERNAME_KEY) ?? '';
  }

  getRoles(): string[] {
    const rol = localStorage.getItem(this.ROL_KEY);
    return rol ? [rol] : [];
  }

  isAdmin(): boolean {
    return this.getRoles().includes('ROLE_ADMIN');
  }

  isAdministrativo(): boolean {
    return this.getRoles().includes('ROLE_ADMINISTRATIVO');
  }

  /** ADMIN o ADMINISTRATIVO — los dos roles que ven información financiera. */
  puedeVerFinanzas(): boolean {
    return this.isAdmin() || this.isAdministrativo();
  }

  /**
   * Limpia el estado local YA (para que la UI redirija al toque) y avisa al
   * backend para que invalide la cookie — best-effort, si falla no importa,
   * total ya borramos las pistas locales y la sesión del browser murió igual.
   */
  logout(): void {
    localStorage.removeItem(this.ROL_KEY);
    localStorage.removeItem(this.EXP_KEY);
    localStorage.removeItem(this.USERNAME_KEY);
    localStorage.removeItem(this.TERMINOS_KEY);
    localStorage.removeItem(this.DEBE_CAMBIAR_KEY);
    if (!environment.useMocks) {
      this.http.post(`${this.gatewayUrl}/api/auth/logout`, {}).subscribe({ error: () => {} });
    }
  }
}
