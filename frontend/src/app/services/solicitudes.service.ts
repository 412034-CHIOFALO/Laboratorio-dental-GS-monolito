import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { delay } from 'rxjs/operators';
import { environment } from '../../environments/environment';
import { PedidoRequest, PedidoResponse, Prioridad } from './pedidos.service';

export type EstadoSolicitud = 'NUEVA' | 'CONVERTIDA' | 'DESCARTADA';

/** Lo que el sistema pudo leer del texto del mail: una sugerencia para completar el formulario, no un dato confirmado. */
export interface SugerenciaSolicitud {
  paciente: string | null;
  trabajo: string | null;
  catalogoTrabajoId: number | null;
  fechaEntrega: string | null;
  prioridad: Prioridad | null;
  precio: number | null;
  observaciones: string | null;
}

export interface AdjuntoSolicitud {
  id: number;
  fileName: string;
  tamanioBytes: number | null;
  tipo: 'DOCUMENTO' | 'ESCANEO';
  formato: string | null;
  triangulos: number | null;
  dimXMm: number | null;
  dimYMm: number | null;
  dimZMm: number | null;
  advertencias: string[];
}

export interface Solicitud {
  id: number;
  canal: 'EMAIL' | 'WEB' | 'WHATSAPP';
  estado: EstadoSolicitud;
  remitenteEmail: string | null;
  remitenteNombre: string | null;
  asunto: string | null;
  cuerpo: string | null;
  fechaMensaje: string | null;
  fechaRecepcion: string;
  /** true si el email del remitente coincide con un odontólogo cargado */
  identificado: boolean;
  odontologoId: number | null;
  odontologoNombre: string | null;
  sugerencia: SugerenciaSolicitud;
  /** Adjuntos que no se guardaron (tipo no permitido, escaneo corrupto, muy pesados...), con el motivo */
  notaAdjuntos: string | null;
  pedidoId: number | null;
  motivoDescarte: string | null;
  resueltoPor: string | null;
  fechaResolucion: string | null;
  adjuntos: AdjuntoSolicitud[];
}

/** Cómo viene la lectura de la casilla: si no responde, que no pase en silencio. */
export interface EstadoCorreo {
  habilitado: boolean;
  ultimaLectura: string | null;
  ultimoExito: string | null;
  ultimoError: string | null;
  recibidas: number;
  ignoradas: number;
  fallidas: number;
}

@Injectable({ providedIn: 'root' })
export class SolicitudesService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.gatewayUrl}/api/pedidos/solicitudes`;

  listar(estado: EstadoSolicitud = 'NUEVA'): Observable<Solicitud[]> {
    if (environment.useMocks) return of(estado === 'NUEVA' ? this.muestra() : []).pipe(delay(200));
    return this.http.get<Solicitud[]>(this.base, { params: new HttpParams().set('estado', estado) });
  }

  cantidadNuevas(): Observable<number> {
    if (environment.useMocks) return of(this.muestra().length).pipe(delay(100));
    return new Observable<number>(sub => {
      this.http.get<{ nuevas: number }>(`${this.base}/cantidad`).subscribe({
        next: r => { sub.next(r.nuevas); sub.complete(); },
        error: e => sub.error(e),
      });
    });
  }

  estadoCorreo(): Observable<EstadoCorreo> {
    if (environment.useMocks) {
      const ahora = new Date().toISOString();
      return of({ habilitado: true, ultimaLectura: ahora, ultimoExito: ahora, ultimoError: null, recibidas: 3, ignoradas: 1, fallidas: 0 }).pipe(delay(100));
    }
    return this.http.get<EstadoCorreo>(`${this.base}/estado-correo`);
  }

  /** Crea el pedido con el formulario ya revisado, le pasa los adjuntos y cierra la solicitud. */
  convertir(id: number, pedido: PedidoRequest): Observable<PedidoResponse> {
    if (environment.useMocks) return of({ id: 999, nroPedido: 'PED-DEMO-0001' } as PedidoResponse).pipe(delay(300));
    return this.http.post<PedidoResponse>(`${this.base}/${id}/convertir`, pedido);
  }

  descartar(id: number, motivo: string | null): Observable<void> {
    if (environment.useMocks) return of(undefined).pipe(delay(150));
    return this.http.post<void>(`${this.base}/${id}/descartar`, { motivo });
  }

  descargarAdjunto(solicitudId: number, adjuntoId: number): Observable<Blob> {
    return this.http.get(`${this.base}/${solicitudId}/adjuntos/${adjuntoId}/archivo`, { responseType: 'blob' });
  }

  // ── Datos de muestra (modo demostración) ──────────────────────────
  private muestra(): Solicitud[] {
    const hoy = new Date();
    const en = (n: number) => { const d = new Date(hoy); d.setDate(d.getDate() + n); return d.toISOString().slice(0, 10); };
    return [
      {
        id: 1, canal: 'EMAIL', estado: 'NUEVA', remitenteEmail: 'perez@clinica.com', remitenteNombre: 'Dr. Carlos Pérez',
        asunto: 'Pedido corona zirconio', cuerpo: 'Hola, les mando una corona de zirconio para la paciente María González, pieza 26, color A2.\nLa necesito para el viernes, es urgente. Acordamos $85.000.\nSaludos, Dr. Pérez',
        fechaMensaje: hoy.toISOString(), fechaRecepcion: hoy.toISOString(), identificado: true, odontologoId: 1, odontologoNombre: 'Dr. Carlos Pérez',
        sugerencia: { paciente: 'María González', trabajo: 'Corona de Zirconio', catalogoTrabajoId: null, fechaEntrega: en(3), prioridad: 'URGENTE', precio: 85000, observaciones: 'Hola, les mando una corona de zirconio para la paciente María González, pieza 26, color A2.' },
        notaAdjuntos: null, pedidoId: null, motivoDescarte: null, resueltoPor: null, fechaResolucion: null,
        adjuntos: [{ id: 1, fileName: 'Arcada superior.stl', tamanioBytes: 4_200_000, tipo: 'ESCANEO', formato: 'STL_BINARIO', triangulos: 84000, dimXMm: 62.4, dimYMm: 48.1, dimZMm: 31, advertencias: [] },
                   { id: 2, fileName: 'receta.pdf', tamanioBytes: 120_000, tipo: 'DOCUMENTO', formato: null, triangulos: null, dimXMm: null, dimYMm: null, dimZMm: null, advertencias: [] }],
      },
      {
        id: 2, canal: 'EMAIL', estado: 'NUEVA', remitenteEmail: 'dra.sosa@odonto.com', remitenteNombre: 'Dra. Sosa',
        asunto: 'Consulta', cuerpo: 'Buenas tardes, quería consultarles cuánto sale aproximadamente un puente de 3 piezas. Gracias!',
        fechaMensaje: hoy.toISOString(), fechaRecepcion: hoy.toISOString(), identificado: false, odontologoId: null, odontologoNombre: null,
        sugerencia: { paciente: null, trabajo: null, catalogoTrabajoId: null, fechaEntrega: null, prioridad: 'NORMAL', precio: null, observaciones: 'Buenas tardes, quería consultarles cuánto sale aproximadamente un puente de 3 piezas. Gracias!' },
        notaAdjuntos: 'virus.exe: tipo de archivo no permitido (se aceptan STL, OBJ, PLY, PDF y fotos)', pedidoId: null, motivoDescarte: null, resueltoPor: null, fechaResolucion: null, adjuntos: [],
      },
    ];
  }
}
