import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { delay } from 'rxjs/operators';
import { environment } from '../../environments/environment';
import { MOCK_MATERIALES_BACKEND, clonar } from './mock-data';

export type CategoriaMaterial =
  | 'YESO' | 'CERAMICA' | 'PORCELANA' | 'ACRILICO' | 'METAL' | 'RESINA'
  | 'ALAMBRE' | 'ZIRCONIA' | 'CERA' | 'ADHESIVO' | 'HERRAMIENTA' | 'CONSUMIBLE' | 'OTRO';

export type TipoMovimiento = 'ENTRADA' | 'SALIDA' | 'AJUSTE';

export interface MaterialResponse {
  id: number;
  nombre: string;
  descripcion: string | null;
  categoria: CategoriaMaterial;
  stockActual: number;
  stockMinimo: number;
  unidadMedida: string;
  precioUnitario: number | null;
  proveedor: string | null;
  activo: boolean;
  /** true = se descuenta cantidad. false = solo se verifica > 0 (esmaltes, etc). */
  descuentaStock: boolean;
  bajoStock: boolean;
  fechaModificacion: string;
}

export interface MaterialRequest {
  nombre: string;
  descripcion?: string | null;
  categoria: CategoriaMaterial;
  stockActual: number;
  stockMinimo: number;
  unidadMedida: string;
  precioUnitario?: number | null;
  proveedor?: string | null;
  descuentaStock: boolean;
}

export interface MovimientoRequest {
  materialId: number;
  tipo: TipoMovimiento;
  cantidad: number;
  motivo?: string | null;
  pedidoId?: number | null;
}

@Injectable({ providedIn: 'root' })
export class StockService {

  private readonly base = `${environment.gatewayUrl}/api/stock`;

  // Mock store en memoria para el modo demo
  private mockStore: MaterialResponse[] = clonar(MOCK_MATERIALES_BACKEND);
  private nextMockId = 1000;

  constructor(private http: HttpClient) {}

  listarActivos(): Observable<MaterialResponse[]> {
    if (environment.useMocks) {
      const activos = this.mockStore.filter(m => m.activo);
      return of(clonar(activos)).pipe(delay(180));
    }
    return this.http.get<MaterialResponse[]>(this.base);
  }

  listarBajoStock(): Observable<MaterialResponse[]> {
    if (environment.useMocks) {
      const bajo = this.mockStore.filter(m => m.activo && m.bajoStock);
      return of(clonar(bajo)).pipe(delay(180));
    }
    return this.http.get<MaterialResponse[]>(`${this.base}/alertas`);
  }

  buscarPorId(id: number): Observable<MaterialResponse> {
    if (environment.useMocks) {
      const m = this.mockStore.find(x => x.id === id);
      if (!m) throw new Error('Material no encontrado');
      return of(clonar(m)).pipe(delay(120));
    }
    return this.http.get<MaterialResponse>(`${this.base}/${id}`);
  }

  crear(request: MaterialRequest): Observable<MaterialResponse> {
    if (environment.useMocks) {
      const nuevo: MaterialResponse = {
        id: this.nextMockId++,
        nombre: request.nombre,
        descripcion: request.descripcion ?? null,
        categoria: request.categoria,
        stockActual: request.stockActual,
        stockMinimo: request.stockMinimo,
        unidadMedida: request.unidadMedida,
        precioUnitario: request.precioUnitario ?? null,
        proveedor: request.proveedor ?? null,
        activo: true,
        descuentaStock: request.descuentaStock,
        bajoStock: request.stockActual <= request.stockMinimo,
        fechaModificacion: new Date().toISOString(),
      };
      this.mockStore.push(nuevo);
      return of(clonar(nuevo)).pipe(delay(220));
    }
    return this.http.post<MaterialResponse>(this.base, request);
  }

  actualizar(id: number, request: MaterialRequest): Observable<MaterialResponse> {
    if (environment.useMocks) {
      const idx = this.mockStore.findIndex(m => m.id === id);
      if (idx === -1) throw new Error('Material no encontrado');
      this.mockStore[idx] = {
        ...this.mockStore[idx],
        nombre: request.nombre,
        descripcion: request.descripcion ?? null,
        categoria: request.categoria,
        stockActual: request.stockActual,
        stockMinimo: request.stockMinimo,
        unidadMedida: request.unidadMedida,
        precioUnitario: request.precioUnitario ?? null,
        proveedor: request.proveedor ?? null,
        descuentaStock: request.descuentaStock,
        bajoStock: request.stockActual <= request.stockMinimo,
        fechaModificacion: new Date().toISOString(),
      };
      return of(clonar(this.mockStore[idx])).pipe(delay(200));
    }
    return this.http.put<MaterialResponse>(`${this.base}/${id}`, request);
  }

  registrarMovimiento(request: MovimientoRequest): Observable<MaterialResponse> {
    if (environment.useMocks) {
      const idx = this.mockStore.findIndex(m => m.id === request.materialId);
      if (idx === -1) throw new Error('Material no encontrado');
      const actual = this.mockStore[idx].stockActual;
      let nuevoStock = actual;
      switch (request.tipo) {
        case 'ENTRADA': nuevoStock = actual + request.cantidad; break;
        case 'SALIDA':  nuevoStock = actual - request.cantidad; break;
        case 'AJUSTE':  nuevoStock = request.cantidad; break;
      }
      this.mockStore[idx] = {
        ...this.mockStore[idx],
        stockActual: nuevoStock,
        bajoStock: nuevoStock <= this.mockStore[idx].stockMinimo,
        fechaModificacion: new Date().toISOString(),
      };
      return of(clonar(this.mockStore[idx])).pipe(delay(220));
    }
    return this.http.post<MaterialResponse>(`${this.base}/movimiento`, request);
  }

  eliminar(id: number): Observable<void> {
    if (environment.useMocks) {
      const idx = this.mockStore.findIndex(m => m.id === id);
      if (idx !== -1) this.mockStore[idx].activo = false;
      return of(void 0).pipe(delay(150));
    }
    return this.http.delete<void>(`${this.base}/${id}`);
  }
}
