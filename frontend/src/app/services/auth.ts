import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';
import { delay } from 'rxjs/operators';
import { environment } from '../../environments/environment';
import { FAKE_JWT, MOCK_USUARIOS, clonar } from './mock-data';

export interface RegisterPayload {
  nombre: string;
  apellido: string;
  username: string;
  password: string;
  rol: 'TECNICO' | 'ADMINISTRATIVO' | 'ODONTOLOGO' | 'ADMIN';
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

interface JwtPayload {
  sub: string;
  roles: string;
  exp: number;
}

@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private gatewayUrl = environment.gatewayUrl;
  private readonly TOKEN_KEY = 'gs_token';
  private readonly TERMINOS_KEY = 'gs_terminos_aceptados';
  private readonly DEBE_CAMBIAR_KEY = 'gs_debe_cambiar_password';

  constructor(private http: HttpClient) {}

  login(username: string, password: string): Observable<{ access_token: string; terminosAceptados: boolean; debeCambiarPassword: boolean }> {
    // MODO DEMO: cualquier user/pass válido entra, ya se considera onboardeado
    if (environment.useMocks) {
      if (username && password) {
        return of({ access_token: FAKE_JWT, terminosAceptados: true, debeCambiarPassword: false }).pipe(delay(400));
      }
      return throwError(() => ({ status: 401, error: { mensaje: 'Credenciales incorrectas' } }));
    }

    // El endpoint de login viene de environment.loginUrl: en dev va por el
    // gateway (:8080) y en prod va relativo (vía nginx). Siempre /api/auth/login.
    return this.http.post<{ access_token: string; terminosAceptados: boolean; debeCambiarPassword: boolean }>(
      environment.loginUrl,
      { username, password }
    );
  }

  aceptarTerminos(): Observable<PerfilResponse> {
    if (environment.useMocks) {
      this.saveTerminosAceptados(true);
      return of({ ...this.mockPerfil(), terminosAceptados: true }).pipe(delay(200));
    }
    return this.http.post<PerfilResponse>(
      `${this.gatewayUrl}/api/auth/me/aceptar-terminos`, {}, { headers: this.authHeaders() }
    );
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
      `${this.gatewayUrl}/api/auth/usuarios/${id}/resetear-password`, {}, { headers: this.authHeaders() }
    );
  }

  listarUsuarios(): Observable<UsuarioListado[]> {
    if (environment.useMocks) {
      return of(clonar(MOCK_USUARIOS) as unknown as UsuarioListado[]).pipe(delay(200));
    }
    return this.http.get<UsuarioListado[]>(`${this.gatewayUrl}/api/auth/usuarios`, { headers: this.authHeaders() });
  }

  register(payload: RegisterPayload): Observable<{ mensaje: string; username: string }> {
    if (environment.useMocks) {
      return of({ mensaje: 'Usuario creado (demo)', username: payload.username }).pipe(delay(300));
    }
    return this.http.post<{ mensaje: string; username: string }>(
      `${this.gatewayUrl}/api/auth/register`,
      payload,
      { headers: this.authHeaders() }
    );
  }

  aprobarUsuario(id: number): Observable<any> {
    if (environment.useMocks) {
      return of({ ok: true }).pipe(delay(200));
    }
    return this.http.put(
      `${this.gatewayUrl}/api/auth/usuarios/${id}/aprobar`,
      {},
      { headers: this.authHeaders() }
    );
  }

  /** Activa/desactiva un integrante (entrada/salida de personal). */
  cambiarEstadoUsuario(id: number, activo: boolean): Observable<any> {
    if (environment.useMocks) {
      return of({ id, enabled: activo }).pipe(delay(200));
    }
    return this.http.patch(
      `${this.gatewayUrl}/api/auth/usuarios/${id}/estado`,
      { activo },
      { headers: this.authHeaders() }
    );
  }

  /** Actualiza el teléfono del integrante (lo usa el bot para identificarlo). */
  actualizarTelefonoUsuario(id: number, telefono: string): Observable<any> {
    if (environment.useMocks) {
      return of({ id, telefono }).pipe(delay(200));
    }
    return this.http.patch(
      `${this.gatewayUrl}/api/auth/usuarios/${id}/telefono`,
      { telefono },
      { headers: this.authHeaders() }
    );
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
        enabled: true, pendienteAprobacion: false, terminosAceptados: true,
      };
    }
    return this._mockPerfil;
  }

  miPerfil(): Observable<PerfilResponse> {
    if (environment.useMocks) return of({ ...this.mockPerfil() }).pipe(delay(150));
    return this.http.get<PerfilResponse>(`${this.gatewayUrl}/api/auth/me`, { headers: this.authHeaders() });
  }

  editarPerfil(req: PerfilUpdate): Observable<PerfilResponse> {
    if (environment.useMocks) {
      this._mockPerfil = { ...this.mockPerfil(), ...req } as PerfilResponse;
      return of({ ...this._mockPerfil }).pipe(delay(250));
    }
    return this.http.patch<PerfilResponse>(`${this.gatewayUrl}/api/auth/me`, req, { headers: this.authHeaders() });
  }

  cambiarPassword(actual: string, nueva: string): Observable<{ mensaje: string }> {
    if (environment.useMocks) {
      if (!actual || actual.length < 6) {
        return throwError(() => ({ status: 400, error: { error: 'La contraseña actual no es correcta.' } }));
      }
      return of({ mensaje: 'Contraseña actualizada correctamente.' }).pipe(delay(250));
    }
    return this.http.post<{ mensaje: string }>(
      `${this.gatewayUrl}/api/auth/me/password`, { actual, nueva }, { headers: this.authHeaders() });
  }

  saveToken(token: string): void {
    localStorage.setItem(this.TOKEN_KEY, token);
  }

  getToken(): string | null {
    return localStorage.getItem(this.TOKEN_KEY);
  }

  isLoggedIn(): boolean {
    const token = this.getToken();
    if (!token) return false;
    try {
      const payload = this.decodePayload(token);
      return payload.exp * 1000 > Date.now();
    } catch {
      return false;
    }
  }

  getUsername(): string {
    const token = this.getToken();
    if (!token) return '';
    try {
      return this.decodePayload(token).sub;
    } catch {
      return '';
    }
  }

  getRoles(): string[] {
    const token = this.getToken();
    if (!token) return [];
    try {
      const roles = this.decodePayload(token).roles;
      return roles ? roles.split(',') : [];
    } catch {
      return [];
    }
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

  logout(): void {
    localStorage.removeItem(this.TOKEN_KEY);
    localStorage.removeItem(this.TERMINOS_KEY);
    localStorage.removeItem(this.DEBE_CAMBIAR_KEY);
  }

  private decodePayload(token: string): JwtPayload {
    const base64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(atob(base64));
  }

  private authHeaders(): HttpHeaders {
    return new HttpHeaders({ Authorization: `Bearer ${this.getToken()}` });
  }
}
