import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { delay } from 'rxjs/operators';
import { environment } from '../../environments/environment';

/**
 * Estado en vivo del bot de WhatsApp. Lo expone el propio bot en
 * `GET /api/bot/estado` (puerto 3001) y el api-gateway lo rutea hacia ahí.
 *
 *  - conectado:    true si el bot tiene la sesión de WhatsApp vinculada y lista.
 *  - qrDataUrl:    PNG (data-URL) del código QR cuando está desvinculado; null si ya está conectado.
 *  - motivo:       texto legible del estado actual ("Conectado", "Esperando escaneo del QR"…).
 *  - grupos:       grupos de WhatsApp que el bot escucha.
 *  - backendActivo:si el bot está enviando al ERP (true) o sólo registrando en consola (false).
 */
export interface EstadoBot {
  conectado: boolean;
  qrDataUrl: string | null;
  motivo: string;
  ultimaActualizacion: string;
  grupos: string[];
  backendActivo: boolean;
}

@Injectable({ providedIn: 'root' })
export class BotEstadoService {
  private http = inject(HttpClient);
  private readonly url = `${environment.gatewayUrl}/api/bot/estado`;

  /** Estado actual del bot. En mocks devuelve la pantalla de vinculación para poder verla sin bot real. */
  estado(): Observable<EstadoBot> {
    if (environment.useMocks) {
      return of(this.mockEstado()).pipe(delay(150));
    }
    return this.http.get<EstadoBot>(this.url);
  }

  /** Desconecta la sesión actual y fuerza la aparición de un nuevo QR. */
  regenerarQr(): Observable<void> {
    if (environment.useMocks) return of(undefined).pipe(delay(500));
    return this.http.post<void>(`${environment.gatewayUrl}/api/bot/regenerar-qr`, {});
  }

  /**
   * Pide al bot que revise el historial reciente de los grupos por si quedaron
   * comprobantes sin cargar (por ejemplo, mientras estuvo desconectado). Corre
   * en segundo plano en el bot — esta llamada solo confirma que arrancó.
   */
  reconciliar(): Observable<{ ok: boolean; mensaje: string }> {
    if (environment.useMocks) {
      return of({ ok: true, mensaje: 'Revisión iniciada (demo).' }).pipe(delay(400));
    }
    return this.http.post<{ ok: boolean; mensaje: string }>(`${environment.gatewayUrl}/api/bot/reconciliar`, {});
  }

  // En modo demo mostramos el estado "desvinculado" con un QR de ejemplo, para
  // que se vea cómo queda la pantalla de re-vinculación sin tener el bot levantado.
  private mockEstado(): EstadoBot {
    return {
      conectado: false,
      qrDataUrl: FAUX_QR,
      motivo: 'Modo demo — así se ve la pantalla cuando el bot se desvincula y hay que volver a escanear el QR.',
      ultimaActualizacion: new Date().toISOString(),
      grupos: ['Comprobantes Transferencias', 'Comprobantes Efectivo'],
      backendActivo: true,
    };
  }
}

/**
 * QR "de mentira" (SVG) sólo para la demo en modo mocks: no es escaneable, pero
 * reproduce el aspecto de un QR real (patrones de esquina + módulos) para que la
 * pantalla de vinculación se vea tal cual sin necesidad de levantar el bot.
 */
function fauxQrDataUrl(): string {
  const N = 29;
  const cells: string[] = [];
  const finder = (ox: number, oy: number) => {
    cells.push(`<rect x="${ox}" y="${oy}" width="7" height="7"/>`);
    cells.push(`<rect x="${ox + 1}" y="${oy + 1}" width="5" height="5" fill="#fff"/>`);
    cells.push(`<rect x="${ox + 2}" y="${oy + 2}" width="3" height="3" fill="#000"/>`);
  };
  finder(0, 0); finder(N - 7, 0); finder(0, N - 7);
  // ruido determinístico para los módulos de datos
  let seed = 7;
  const rnd = () => (seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff;
  for (let y = 0; y < N; y++) {
    for (let x = 0; x < N; x++) {
      const enFinder = (x < 8 && y < 8) || (x > N - 9 && y < 8) || (x < 8 && y > N - 9);
      if (enFinder) continue;
      if (rnd() > 0.55) cells.push(`<rect x="${x}" y="${y}" width="1" height="1"/>`);
    }
  }
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${N} ${N}" shape-rendering="crispEdges">` +
    `<rect width="${N}" height="${N}" fill="#fff"/><g fill="#000">${cells.join('')}</g></svg>`;
  return 'data:image/svg+xml,' + encodeURIComponent(svg);
}

const FAUX_QR = fauxQrDataUrl();
