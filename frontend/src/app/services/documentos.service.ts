import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { delay } from 'rxjs/operators';
import { environment } from '../../environments/environment';

export interface DocumentoResponse {
  id: number;
  pedidoId: number;
  fileName: string;
  contentType: string | null;
  tamanioBytes: number | null;
  subidoPor: string | null;
  fechaSubida: string;
}

@Injectable({ providedIn: 'root' })
export class DocumentosService {

  private base(pedidoId: number): string {
    return `${environment.gatewayUrl}/api/pedidos/${pedidoId}/docs`;
  }

  constructor(private http: HttpClient) {}

  listar(pedidoId: number): Observable<DocumentoResponse[]> {
    if (environment.useMocks) return of([]).pipe(delay(200));
    return this.http.get<DocumentoResponse[]>(this.base(pedidoId));
  }

  subir(pedidoId: number, file: File): Observable<DocumentoResponse> {
    if (environment.useMocks) {
      const mock: DocumentoResponse = {
        id: Date.now(),
        pedidoId,
        fileName: file.name,
        contentType: file.type || null,
        tamanioBytes: file.size,
        subidoPor: 'demo',
        fechaSubida: new Date().toISOString(),
      };
      return of(mock).pipe(delay(600));
    }
    const fd = new FormData();
    fd.append('file', file);
    return this.http.post<DocumentoResponse>(this.base(pedidoId), fd);
  }

  eliminar(pedidoId: number, docId: number): Observable<void> {
    if (environment.useMocks) return of(undefined).pipe(delay(300));
    return this.http.delete<void>(`${this.base(pedidoId)}/${docId}`);
  }

  /** Descarga el archivo en streaming a través del gateway (sin exponer MinIO). */
  descargar(pedidoId: number, docId: number): Observable<Blob> {
    return this.http.get(`${this.base(pedidoId)}/${docId}/archivo`, { responseType: 'blob' });
  }
}
