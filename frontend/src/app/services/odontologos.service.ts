import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { delay } from 'rxjs/operators';
import { environment } from '../../environments/environment';
import { MOCK_ODONTOLOGOS, clonar } from './mock-data';

export interface OdontologoResponse {
  id: number;
  nombre: string;
  dni: string | null;
  cuit: string | null;
  telefono: string | null;
  email: string | null;
  matricula: string | null;
  clinica: string | null;
  direccion: string | null;
  activo: boolean;
  fechaCreacion: string;
  fechaModificacion: string;
  // ── Estado de actividad (calculado en backend por último pedido) ──
  ultimoPedido?: string | null;
  inactivoPorTiempo?: boolean;
}

export interface OdontologoRequest {
  nombre: string;
  dni?: string | null;
  cuit?: string | null;
  telefono?: string | null;
  email?: string | null;
  matricula?: string | null;
  clinica?: string | null;
  direccion?: string | null;
}

@Injectable({ providedIn: 'root' })
export class OdontologosService {

  private readonly base = `${environment.gatewayUrl}/api/odontologos`;

  // Mock store en memoria — para crear/buscar/agregar funcione sin backend
  private mockStore: OdontologoResponse[] = clonar(MOCK_ODONTOLOGOS);
  private nextMockId = 100;

  constructor(private http: HttpClient) {}

  /**
   * Búsqueda inteligente: el backend detecta automáticamente si lo enviado es
   * DNI, CUIT, matrícula o fragmento de nombre. En modo mocks replicamos esa lógica.
   */
  buscar(q?: string, incluirInactivos = false): Observable<OdontologoResponse[]> {
    if (environment.useMocks) {
      let resultados = (incluirInactivos ? this.mockStore : this.mockStore.filter(o => o.activo))
        .map(o => this.enriquecerActividad(o));
      if (q && q.trim()) {
        const valor = q.trim();
        const lower = valor.toLowerCase();

        const esDNI = /^[0-9]{7,8}$/.test(valor);
        const esCUIT = /^[0-9]{2}-?[0-9]{8}-?[0-9]{1}$/.test(valor);
        const esMatricula = /^(MN|MP|MAT)[\s-]*[0-9]+$/i.test(valor);

        if (esDNI) {
          resultados = resultados.filter(o => o.dni === valor);
        } else if (esCUIT) {
          const cuitNorm = this.normalizarCuit(valor);
          resultados = resultados.filter(o => o.cuit === cuitNorm);
        } else if (esMatricula) {
          resultados = resultados.filter(o => o.matricula?.toLowerCase() === lower);
        } else {
          resultados = resultados.filter(o => o.nombre.toLowerCase().includes(lower));
        }
      }
      return of(clonar(resultados)).pipe(delay(150));
    }

    let params = new HttpParams();
    if (q && q.trim()) params = params.set('q', q.trim());
    if (incluirInactivos) params = params.set('incluirInactivos', 'true');
    return this.http.get<OdontologoResponse[]>(this.base, { params });
  }

  /**
   * Mock: simula el estado de actividad. Para demostrar el filtro, marcamos
   * como inactivos (sin pedidos recientes) algunos odontólogos según su id.
   */
  private enriquecerActividad(o: OdontologoResponse): OdontologoResponse {
    if (o.inactivoPorTiempo !== undefined) return o;
    // demo: ids pares = activos recientes, algunos ids = inactivos
    const inactivo = [3, 5, 8].includes(o.id);
    const dias = inactivo ? 240 : 12;
    const fecha = new Date();
    fecha.setDate(fecha.getDate() - dias);
    return { ...o, inactivoPorTiempo: inactivo, ultimoPedido: fecha.toISOString() };
  }

  private normalizarCuit(cuit: string): string {
    const digitos = cuit.replace(/[^0-9]/g, '');
    if (digitos.length !== 11) return cuit;
    return `${digitos.slice(0, 2)}-${digitos.slice(2, 10)}-${digitos.slice(10)}`;
  }

  buscarPorId(id: number): Observable<OdontologoResponse> {
    if (environment.useMocks) {
      const o = this.mockStore.find(x => x.id === id);
      if (!o) throw new Error('Odontólogo no encontrado');
      return of(clonar(o)).pipe(delay(120));
    }
    return this.http.get<OdontologoResponse>(`${this.base}/${id}`);
  }

  crear(request: OdontologoRequest): Observable<OdontologoResponse> {
    if (environment.useMocks) {
      const ahora = new Date().toISOString();
      const nuevo: OdontologoResponse = {
        id: this.nextMockId++,
        nombre: request.nombre.trim(),
        dni: request.dni ?? null,
        cuit: request.cuit ? this.normalizarCuit(request.cuit) : null,
        telefono: request.telefono ?? null,
        email: request.email ?? null,
        matricula: request.matricula ?? null,
        clinica: request.clinica ?? null,
        direccion: request.direccion ?? null,
        activo: true,
        fechaCreacion: ahora,
        fechaModificacion: ahora,
      };
      this.mockStore.push(nuevo);
      return of(clonar(nuevo)).pipe(delay(250));
    }
    return this.http.post<OdontologoResponse>(this.base, request);
  }

  actualizar(id: number, request: OdontologoRequest): Observable<OdontologoResponse> {
    if (environment.useMocks) {
      const idx = this.mockStore.findIndex(o => o.id === id);
      if (idx === -1) throw new Error('Odontólogo no encontrado');
      this.mockStore[idx] = {
        ...this.mockStore[idx],
        nombre: request.nombre.trim(),
        dni: request.dni ?? null,
        cuit: request.cuit ? this.normalizarCuit(request.cuit) : null,
        telefono: request.telefono ?? null,
        email: request.email ?? null,
        matricula: request.matricula ?? null,
        clinica: request.clinica ?? null,
        direccion: request.direccion ?? null,
        fechaModificacion: new Date().toISOString(),
      };
      return of(clonar(this.mockStore[idx])).pipe(delay(200));
    }
    return this.http.put<OdontologoResponse>(`${this.base}/${id}`, request);
  }

  desactivar(id: number): Observable<void> {
    if (environment.useMocks) {
      const idx = this.mockStore.findIndex(o => o.id === id);
      if (idx !== -1) this.mockStore[idx].activo = false;
      return of(void 0).pipe(delay(180));
    }
    return this.http.delete<void>(`${this.base}/${id}`);
  }
}
