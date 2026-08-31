import { Injectable } from '@angular/core';
import { HttpClient, HttpEvent, HttpRequest, HttpResponse } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { delay } from 'rxjs/operators';
import { environment } from '../../environments/environment';

export interface EscaneoResponse {
  id: number;
  pedidoId: number;
  fileName: string;
  contentType: string | null;
  tamanioBytes: number | null;
  descripcion: string | null;
  subidoPor: string | null;
  fechaSubida: string;
}

@Injectable({ providedIn: 'root' })
export class EscaneosService {

  private base(pedidoId: number): string {
    return `${environment.gatewayUrl}/api/pedidos/${pedidoId}/escaneos`;
  }

  constructor(private http: HttpClient) {}

  listar(pedidoId: number): Observable<EscaneoResponse[]> {
    if (environment.useMocks) {
      // Escaneo de muestra para poder demostrar el visor 3D sin backend.
      const muestra: EscaneoResponse = {
        id: 9000 + pedidoId,
        pedidoId,
        fileName: 'modelo-muestra.stl',
        contentType: 'model/stl',
        tamanioBytes: 2048,
        descripcion: 'Modelo de demostración',
        subidoPor: 'demo',
        fechaSubida: new Date().toISOString(),
      };
      return of([muestra]).pipe(delay(200));
    }
    return this.http.get<EscaneoResponse[]>(this.base(pedidoId));
  }

  /** Descarga el archivo en streaming a través del gateway (sin exponer MinIO). */
  descargar(pedidoId: number, escaneoId: number): Observable<Blob> {
    if (environment.useMocks) {
      return this.http.get('/sample-escaneo.stl', { responseType: 'blob' });
    }
    return this.http.get(`${this.base(pedidoId)}/${escaneoId}/archivo`, { responseType: 'blob' });
  }

  /** Sube el archivo reportando progreso (útil para STL grandes: sin esto la barra queda "colgada"). */
  subir(pedidoId: number, file: File, descripcion?: string): Observable<HttpEvent<EscaneoResponse>> {
    if (environment.useMocks) {
      const mock: EscaneoResponse = {
        id: Date.now(), pedidoId,
        fileName: file.name,
        contentType: file.type || 'model/stl',
        tamanioBytes: file.size,
        descripcion: descripcion ?? null,
        subidoPor: 'demo',
        fechaSubida: new Date().toISOString(),
      };
      return of(new HttpResponse({ body: mock, status: 200 })).pipe(delay(700));
    }
    const fd = new FormData();
    fd.append('file', file);
    if (descripcion) fd.append('descripcion', descripcion);
    const req = new HttpRequest<FormData>('POST', this.base(pedidoId), fd, { reportProgress: true });
    return this.http.request<EscaneoResponse>(req);
  }

  eliminar(pedidoId: number, escaneoId: number): Observable<void> {
    if (environment.useMocks) return of(undefined).pipe(delay(300));
    return this.http.delete<void>(`${this.base(pedidoId)}/${escaneoId}`);
  }
}
