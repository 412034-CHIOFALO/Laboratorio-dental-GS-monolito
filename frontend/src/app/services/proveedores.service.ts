import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { delay } from 'rxjs/operators';
import { environment } from '../../environments/environment';

export type EstadoDeuda = 'PENDIENTE' | 'PARCIAL' | 'PAGADO';
/** Caja de la que sale un pago manual a proveedor (no incluye COMPENSACION: esa es solo para triangulados del bot). */
export type CajaPagoProveedor = 'FISICA' | 'BANCARIA';

export interface Proveedor {
  id: number;
  nombre: string;
  cuit: string | null;
  email: string | null;
  telefono: string | null;
  direccion: string | null;
  activo: boolean;
  deudaPendiente: number;   // total pendiente, ya calculado por el backend
}

export interface DeudaProveedor {
  id: number;
  proveedorId: number;
  proveedorNombre: string;
  descripcion: string;
  monto: number;
  montoPagado: number;
  saldoPendiente: number;
  estado: EstadoDeuda;      // PAGADO = ya saldada (ej: por un triangulado del bot)
  fechaVencimiento: string | null;
  fechaPago: string | null;
  nroFacturaProveedor: string | null;
  observaciones: string | null;
}

export interface ProveedorRequest {
  nombre: string;
  cuit?: string | null;
  email?: string | null;
  telefono?: string | null;
  direccion?: string | null;
}

export interface DeudaProveedorRequest {
  proveedorId: number;
  descripcion: string;
  monto: number;
  fechaVencimiento?: string | null;
  nroFacturaProveedor?: string | null;
  observaciones?: string | null;
}

@Injectable({ providedIn: 'root' })
export class ProveedoresService {
  private http = inject(HttpClient);
  private readonly base = `${environment.gatewayUrl}/api/finanzas/proveedores`;

  listar(): Observable<Proveedor[]> {
    if (environment.useMocks) return of(this.mockProveedores()).pipe(delay(220));
    return this.http.get<Proveedor[]>(this.base);
  }

  deudas(proveedorId: number): Observable<DeudaProveedor[]> {
    if (environment.useMocks) {
      return of(this.mockDeudas().filter(d => d.proveedorId === proveedorId)).pipe(delay(180));
    }
    return this.http.get<DeudaProveedor[]>(`${this.base}/${proveedorId}/deudas`);
  }

  crear(req: ProveedorRequest): Observable<Proveedor> {
    if (environment.useMocks) {
      const nuevo: Proveedor = {
        id: Math.floor(Math.random() * 9000) + 1000,
        nombre: req.nombre, cuit: req.cuit ?? null, email: req.email ?? null,
        telefono: req.telefono ?? null, direccion: req.direccion ?? null,
        activo: true, deudaPendiente: 0,
      };
      return of(nuevo).pipe(delay(200));
    }
    return this.http.post<Proveedor>(this.base, req);
  }

  /** Registra una nueva deuda (compra de materiales) con un proveedor. */
  registrarDeuda(req: DeudaProveedorRequest): Observable<DeudaProveedor> {
    if (environment.useMocks) {
      const nueva: DeudaProveedor = {
        id: Math.floor(Math.random() * 9000) + 1000,
        proveedorId: req.proveedorId,
        proveedorNombre: this.mockProveedores().find(p => p.id === req.proveedorId)?.nombre ?? '',
        descripcion: req.descripcion,
        monto: req.monto,
        montoPagado: 0,
        saldoPendiente: req.monto,
        estado: 'PENDIENTE',
        fechaVencimiento: req.fechaVencimiento ?? null,
        fechaPago: null,
        nroFacturaProveedor: req.nroFacturaProveedor ?? null,
        observaciones: req.observaciones ?? null,
      };
      return of(nueva).pipe(delay(200));
    }
    return this.http.post<DeudaProveedor>(`${this.base}/deudas`, req);
  }

  /** Deudas ya pagadas manualmente (no por el bot), más recientes primero — usado en Finanzas → Triangulados para completar el historial. */
  deudasPagadas(): Observable<DeudaProveedor[]> {
    if (environment.useMocks) {
      return of(this.mockDeudas().filter(d => d.estado === 'PAGADO')).pipe(delay(180));
    }
    return this.http.get<DeudaProveedor[]>(`${this.base}/deudas/pagadas`);
  }

  /** Marca una deuda como pagada (fecha de pago = hoy) y registra el egreso en la caja indicada. */
  pagarDeuda(id: number, caja: CajaPagoProveedor): Observable<DeudaProveedor> {
    if (environment.useMocks) {
      const d = this.mockDeudas().find(x => x.id === id)!;
      return of({ ...d, estado: 'PAGADO' as EstadoDeuda, montoPagado: d.monto, saldoPendiente: 0, fechaPago: new Date().toISOString().slice(0, 10) }).pipe(delay(200));
    }
    const params = new HttpParams().set('caja', caja);
    return this.http.patch<DeudaProveedor>(`${this.base}/deudas/${id}/pagar`, null, { params });
  }

  // ── Mocks ──
  private mockProveedores(): Proveedor[] {
    return [
      { id: 1, nombre: 'Dental Import SRL', cuit: '30-71234567-8', email: 'ventas@dentalimport.com', telefono: '11-4555-0001', direccion: 'Av. Corrientes 1234, CABA', activo: true, deudaPendiente: 0 },
      { id: 2, nombre: 'Luciano Giménez', cuit: '20-45700585-8', email: null, telefono: '351-700-2020', direccion: null, activo: true, deudaPendiente: 0 },
      { id: 3, nombre: 'Protésica del Sur', cuit: '30-70999888-1', email: 'info@protesicadelsur.com', telefono: '341-200-3030', direccion: 'Rosario, Santa Fe', activo: true, deudaPendiente: 48000 },
    ];
  }

  private mockDeudas(): DeudaProveedor[] {
    return [
      { id: 1, proveedorId: 1, proveedorNombre: 'Dental Import SRL', descripcion: 'Cerámica Vita PM9 — Lote 2025-05', monto: 25000, montoPagado: 25000, saldoPendiente: 0, estado: 'PAGADO', fechaVencimiento: '2026-07-09', fechaPago: '2026-06-10', nroFacturaProveedor: 'A-0001-00045', observaciones: null },
      { id: 2, proveedorId: 2, proveedorNombre: 'Luciano Giménez', descripcion: 'Fresado tercerizado — Lote 2025-06', monto: 12000, montoPagado: 12000, saldoPendiente: 0, estado: 'PAGADO', fechaVencimiento: '2026-06-30', fechaPago: '2026-06-10', nroFacturaProveedor: null, observaciones: 'Saldada por triangulado (Dra. Laura Sánchez)' },
      { id: 3, proveedorId: 3, proveedorNombre: 'Protésica del Sur', descripcion: 'Coronas de zirconio x10', monto: 30000, montoPagado: 0, saldoPendiente: 30000, estado: 'PENDIENTE', fechaVencimiento: '2026-07-01', fechaPago: null, nroFacturaProveedor: 'B-0003-00012', observaciones: null },
      { id: 4, proveedorId: 3, proveedorNombre: 'Protésica del Sur', descripcion: 'Implantes premium x4', monto: 18000, montoPagado: 0, saldoPendiente: 18000, estado: 'PENDIENTE', fechaVencimiento: '2026-07-15', fechaPago: null, nroFacturaProveedor: 'B-0003-00020', observaciones: null },
    ];
  }
}
