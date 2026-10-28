import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { delay } from 'rxjs/operators';
import { environment } from '../../environments/environment';

/**
 * A dónde llegó una copia: `local` (solo el disco del servidor), `drive` (también Google Drive), `drive-fallo`
 * (Drive está configurado pero la subida falló) o `desconocido` (copia hecha antes de que se registrara el destino).
 */
export type DestinoCopia = 'local' | 'drive' | 'drive-fallo' | 'desconocido';

export interface CopiaSeguridad {
  /** AAAA-MM-DD_HH-MM, hora del servidor. */
  nombre: string;
  bytes: number;
  destino: DestinoCopia;
}

export interface EstadoBackup {
  enCurso: boolean;
  driveConfigurado: boolean;
  /** De la más nueva a la más vieja. */
  copias: CopiaSeguridad[];
}

/** Copias de seguridad del sistema: hacerlas ahora, ver las que hay y bajarlas a la computadora (solo ADMIN). */
@Injectable({ providedIn: 'root' })
export class BackupService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.gatewayUrl}/api/auth/backup`;

  /** Copias de la demo (sin backend): «Hacer backup ahora» agrega una, como haría el sistema real. */
  private readonly copiasDemo: CopiaSeguridad[] = [
    { nombre: '2026-10-28_03-00', bytes: 231_424, destino: 'local' },
    { nombre: '2026-10-27_03-00', bytes: 229_376, destino: 'local' },
  ];

  estado(): Observable<EstadoBackup> {
    if (environment.useMocks) {
      return of<EstadoBackup>({ enCurso: false, driveConfigurado: false, copias: [...this.copiasDemo] }).pipe(delay(200));
    }
    return this.http.get<EstadoBackup>(`${this.base}/estado`);
  }

  ejecutar(): Observable<unknown> {
    if (environment.useMocks) {
      setTimeout(() => this.copiasDemo.unshift({ nombre: this.nombreDeAhora(), bytes: 233_472, destino: 'local' }), 1500);
      return of({ ok: true }).pipe(delay(300));
    }
    return this.http.post(`${this.base}/run`, {});
  }

  /** El .tar de la copia, en streaming desde el servidor. */
  descargar(nombre: string): Observable<Blob> {
    if (environment.useMocks) return of(new Blob(['copia de demostración'])).pipe(delay(300));
    return this.http.get(`${this.base}/copias/${encodeURIComponent(nombre)}/archivo`, { responseType: 'blob' });
  }

  private nombreDeAhora(): string {
    const d = new Date();
    const dos = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${dos(d.getMonth() + 1)}-${dos(d.getDate())}_${dos(d.getHours())}-${dos(d.getMinutes())}`;
  }
}
