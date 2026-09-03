import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';
import { delay } from 'rxjs/operators';
import { environment } from '../../environments/environment';
import { MOCK_CATALOGO, clonar } from './mock-data';

export type Categoria = 'FIJA' | 'REMOVIBLE' | 'ORTODONCIA' | 'ATM' | 'PERSONALIZADO';

export interface IngredienteRecetaResponse {
  id: number;
  materialId: number;
  materialNombre: string;
  cantidad: number;
  unidad: string | null;
  notas: string | null;
}

export interface IngredienteRecetaRequest {
  materialId: number;
  materialNombre: string;
  cantidad: number;
  unidad?: string | null;
  notas?: string | null;
}

export interface TipoTrabajoResponse {
  id: number;
  nombre: string;
  descripcion: string;
  precio: number;
  categoria: Categoria;
  tiempoEstimadoDias: number;
  fotoUrl: string | null;
  activo: boolean;
  receta: IngredienteRecetaResponse[];
  fechaCreacion: string;
  fechaModificacion: string;
}

export interface TipoTrabajoRequest {
  nombre: string;
  descripcion: string;
  precio: number;
  categoria: Categoria;
  tiempoEstimadoDias: number;
  fotoUrl?: string | null;
  receta?: IngredienteRecetaRequest[];
}

/** Espejo de TipoTrabajoResponse para /api/publico/catalogo — sin receta a propósito. */
export interface TipoTrabajoPublicoResponse {
  id: number;
  nombre: string;
  descripcion: string;
  precio: number;
  categoria: Categoria;
  tiempoEstimadoDias: number;
  fotoUrl: string | null;
}

export interface ConfiguracionCatalogoPublicoResponse {
  habilitado: boolean;
}

@Injectable({ providedIn: 'root' })
export class CatalogoService {

  private readonly base = `${environment.apiUrl}/api/catalogo`;

  // Estado mock en memoria (se mutila para que crear/editar/eliminar funcionen visualmente)
  private mockStore: TipoTrabajoResponse[] = clonar(MOCK_CATALOGO);
  private nextMockId = 11;
  // Arranca deshabilitado — mismo default que el backend real (ver
  // ConfiguracionCatalogoPublico), para que el modo demo se comporte igual.
  private mockCatalogoPublicoHabilitado = false;

  constructor(private http: HttpClient) {}

  listar(categoria?: string, nombre?: string): Observable<TipoTrabajoResponse[]> {
    if (environment.useMocks) {
      let filtrados = this.mockStore.filter(t => t.activo);
      if (categoria && categoria !== 'TODOS') {
        filtrados = filtrados.filter(t => t.categoria === categoria);
      }
      if (nombre && nombre.trim()) {
        const q = nombre.trim().toLowerCase();
        filtrados = filtrados.filter(t => t.nombre.toLowerCase().includes(q));
      }
      return of(clonar(filtrados)).pipe(delay(200));
    }

    let params = new HttpParams();
    if (categoria && categoria !== 'TODOS') params = params.set('categoria', categoria);
    if (nombre && nombre.trim())            params = params.set('nombre', nombre.trim());
    return this.http.get<TipoTrabajoResponse[]>(this.base, { params });
  }

  crear(request: TipoTrabajoRequest): Observable<TipoTrabajoResponse> {
    if (environment.useMocks) {
      const HOY = new Date().toISOString();
      let nextItemId = 1000;
      const nuevo: TipoTrabajoResponse = {
        id: this.nextMockId++,
        ...request,
        fotoUrl: request.fotoUrl ?? null,
        activo: true,
        receta: (request.receta ?? []).map(r => ({ id: nextItemId++, ...r, unidad: r.unidad ?? null, notas: r.notas ?? null })),
        fechaCreacion: HOY,
        fechaModificacion: HOY,
      };
      this.mockStore.push(nuevo);
      return of(clonar(nuevo)).pipe(delay(300));
    }
    return this.http.post<TipoTrabajoResponse>(this.base, request);
  }

  actualizar(id: number, request: TipoTrabajoRequest): Observable<TipoTrabajoResponse> {
    if (environment.useMocks) {
      const idx = this.mockStore.findIndex(t => t.id === id);
      if (idx === -1) throw new Error('No encontrado');
      let nextItemId = Date.now();
      this.mockStore[idx] = {
        ...this.mockStore[idx],
        ...request,
        fotoUrl: request.fotoUrl ?? this.mockStore[idx].fotoUrl,
        receta: (request.receta ?? []).map(r => ({ id: nextItemId++, ...r, unidad: r.unidad ?? null, notas: r.notas ?? null })),
        fechaModificacion: new Date().toISOString(),
      };
      return of(clonar(this.mockStore[idx])).pipe(delay(250));
    }
    return this.http.put<TipoTrabajoResponse>(`${this.base}/${id}`, request);
  }

  eliminar(id: number): Observable<void> {
    if (environment.useMocks) {
      const idx = this.mockStore.findIndex(t => t.id === id);
      if (idx !== -1) {
        this.mockStore[idx].activo = false;
      }
      return of(void 0).pipe(delay(200));
    }
    return this.http.delete<void>(`${this.base}/${id}`);
  }

  // ─── Catálogo público (sin login) ──────────────────────────────────────

  catalogoPublicoHabilitado(): Observable<ConfiguracionCatalogoPublicoResponse> {
    if (environment.useMocks) {
      return of({ habilitado: this.mockCatalogoPublicoHabilitado }).pipe(delay(150));
    }
    return this.http.get<ConfiguracionCatalogoPublicoResponse>(`${environment.apiUrl}/api/publico/catalogo/habilitado`);
  }

  listarPublico(): Observable<TipoTrabajoPublicoResponse[]> {
    if (environment.useMocks) {
      if (!this.mockCatalogoPublicoHabilitado) {
        return throwError(() => ({ status: 404 })).pipe(delay(150));
      }
      const publicos: TipoTrabajoPublicoResponse[] = this.mockStore
        .filter(t => t.activo)
        .map(({ id, nombre, descripcion, precio, categoria, tiempoEstimadoDias, fotoUrl }) =>
          ({ id, nombre, descripcion, precio, categoria, tiempoEstimadoDias, fotoUrl }));
      return of(clonar(publicos)).pipe(delay(200));
    }
    return this.http.get<TipoTrabajoPublicoResponse[]>(`${environment.apiUrl}/api/publico/catalogo`);
  }

  // ─── Configuración (ADMIN) ──────────────────────────────────────────────

  obtenerConfiguracionPublica(): Observable<ConfiguracionCatalogoPublicoResponse> {
    if (environment.useMocks) {
      return of({ habilitado: this.mockCatalogoPublicoHabilitado }).pipe(delay(150));
    }
    return this.http.get<ConfiguracionCatalogoPublicoResponse>(`${this.base}/configuracion-publica`);
  }

  actualizarConfiguracionPublica(habilitado: boolean): Observable<ConfiguracionCatalogoPublicoResponse> {
    if (environment.useMocks) {
      this.mockCatalogoPublicoHabilitado = habilitado;
      return of({ habilitado }).pipe(delay(250));
    }
    return this.http.put<ConfiguracionCatalogoPublicoResponse>(`${this.base}/configuracion-publica`, { habilitado });
  }
}
