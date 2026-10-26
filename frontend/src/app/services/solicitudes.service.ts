import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { delay } from 'rxjs/operators';
import { environment } from '../../environments/environment';
import { PedidoRequest, PedidoResponse, Prioridad } from './pedidos.service';

export type EstadoSolicitud = 'NUEVA' | 'CONVERTIDA' | 'DESCARTADA' | 'SIN_AUTORIZAR';

/** Quién es el remitente de un mail para el laboratorio. */
export type RemitenteTipo = 'ODONTOLOGO' | 'SECRETARIA' | 'OTRO';

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
  /** Quién es el remitente (en «sin autorizar», lo que afirma ser, no algo confirmado) */
  remitenteTipo: RemitenteTipo | null;
  remitenteEtiqueta: string | null;
  /** Por qué el mail quedó aparte, esperando que alguien decida */
  motivoCuarentena: string | null;
  /** true = el servidor de correo comprobó que el mail es de esa dirección; false = no lo pudo comprobar */
  remitenteVerificado: boolean | null;
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
  sinAutorizar: number;
}

export interface CantidadesSolicitudes {
  nuevas: number;
  sinAutorizar: number;
}

/** Una dirección que el laboratorio autorizó o bloqueó. */
export interface Remitente {
  id: number;
  email: string;
  tipo: RemitenteTipo;
  odontologoId: number | null;
  etiqueta: string | null;
  estado: 'AUTORIZADO' | 'BLOQUEADO';
  creadoPor: string;
  fechaCreacion: string;
}

export interface RemitenteRequest {
  email: string;
  tipo: RemitenteTipo;
  odontologoId: number | null;
  aclaracion: string | null;
}

export interface AceptarRemitenteRequest {
  tipo: RemitenteTipo;
  odontologoId: number | null;
  aclaracion: string | null;
  /** true = los próximos mails de esa dirección entran directo a «Por revisar» */
  recordar: boolean;
  /** Si el odontólogo todavía no está cargado: se lo da de alta en el mismo paso (en vez de elegir uno de la lista). */
  nuevoOdontologo: { nombre: string; clinica: string | null; telefono: string | null } | null;
}

@Injectable({ providedIn: 'root' })
export class SolicitudesService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.gatewayUrl}/api/pedidos/solicitudes`;

  listar(estado: EstadoSolicitud = 'NUEVA'): Observable<Solicitud[]> {
    if (environment.useMocks) {
      return of(this.muestra().filter(x => x.estado === estado)).pipe(delay(200));
    }
    return this.http.get<Solicitud[]>(this.base, { params: new HttpParams().set('estado', estado) });
  }

  /** Cuántas esperan revisión y cuántas quedaron aparte por venir de un remitente sin autorizar. */
  cantidades(): Observable<CantidadesSolicitudes> {
    if (environment.useMocks) {
      const m = this.muestra();
      return of({ nuevas: m.filter(x => x.estado === 'NUEVA').length, sinAutorizar: m.filter(x => x.estado === 'SIN_AUTORIZAR').length }).pipe(delay(100));
    }
    return this.http.get<CantidadesSolicitudes>(`${this.base}/cantidad`);
  }

  estadoCorreo(): Observable<EstadoCorreo> {
    if (environment.useMocks) {
      const ahora = new Date().toISOString();
      return of({ habilitado: true, ultimaLectura: ahora, ultimoExito: ahora, ultimoError: null, recibidas: 3, ignoradas: 1, fallidas: 0, sinAutorizar: 1 }).pipe(delay(100));
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

  /** Una persona dice quién es el remitente: el mail pasa a «Por revisar» y, si se pide, la dirección queda autorizada. */
  aceptar(id: number, req: AceptarRemitenteRequest): Observable<Solicitud> {
    if (environment.useMocks) return of(this.muestra().find(x => x.id === id) as Solicitud).pipe(delay(200));
    return this.http.post<Solicitud>(`${this.base}/${id}/aceptar`, req);
  }

  /** Bloquea al remitente: se descartan sus mails que esperaban y los próximos se ignoran. */
  bloquearRemitente(id: number): Observable<void> {
    if (environment.useMocks) return of(undefined).pipe(delay(150));
    return this.http.post<void>(`${this.base}/${id}/bloquear-remitente`, {});
  }

  remitentes(): Observable<Remitente[]> {
    if (environment.useMocks) {
      return of<Remitente[]>([
        { id: 1, email: 'laura@consultorio-perez.com', tipo: 'SECRETARIA', odontologoId: 1, etiqueta: 'Secretaria de Dr. Carlos Pérez', estado: 'AUTORIZADO', creadoPor: 'admin', fechaCreacion: new Date().toISOString() },
        { id: 2, email: 'promos@ofertas-raras.com', tipo: 'OTRO', odontologoId: null, etiqueta: 'Bloqueado', estado: 'BLOQUEADO', creadoPor: 'admin', fechaCreacion: new Date().toISOString() },
      ]).pipe(delay(150));
    }
    return this.http.get<Remitente[]>(`${this.base}/remitentes`);
  }

  autorizarRemitente(req: RemitenteRequest): Observable<Remitente> {
    if (environment.useMocks) return of({ id: 99, email: req.email, tipo: req.tipo, odontologoId: req.odontologoId, etiqueta: req.aclaracion, estado: 'AUTORIZADO', creadoPor: 'admin', fechaCreacion: new Date().toISOString() } as Remitente).pipe(delay(150));
    return this.http.post<Remitente>(`${this.base}/remitentes`, req);
  }

  quitarRemitente(id: number): Observable<void> {
    if (environment.useMocks) return of(undefined).pipe(delay(150));
    return this.http.delete<void>(`${this.base}/remitentes/${id}`);
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
        remitenteTipo: 'ODONTOLOGO', remitenteEtiqueta: 'Dr. Carlos Pérez', motivoCuarentena: null, remitenteVerificado: true,
        sugerencia: { paciente: 'María González', trabajo: 'Corona de Zirconio', catalogoTrabajoId: null, fechaEntrega: en(3), prioridad: 'URGENTE', precio: 85000, observaciones: 'Hola, les mando una corona de zirconio para la paciente María González, pieza 26, color A2.' },
        notaAdjuntos: null, pedidoId: null, motivoDescarte: null, resueltoPor: null, fechaResolucion: null,
        adjuntos: [{ id: 1, fileName: 'Arcada superior.stl', tamanioBytes: 4_200_000, tipo: 'ESCANEO', formato: 'STL_BINARIO', triangulos: 84000, dimXMm: 62.4, dimYMm: 48.1, dimZMm: 31, advertencias: [] },
                   { id: 2, fileName: 'receta.pdf', tamanioBytes: 120_000, tipo: 'DOCUMENTO', formato: null, triangulos: null, dimXMm: null, dimYMm: null, dimZMm: null, advertencias: [] }],
      },
      {
        id: 2, canal: 'EMAIL', estado: 'SIN_AUTORIZAR', remitenteEmail: 'dra.sosa@odonto.com', remitenteNombre: 'Dra. Sosa',
        asunto: 'Consulta', cuerpo: 'Buenas tardes, quería consultarles cuánto sale aproximadamente un puente de 3 piezas. Gracias!',
        fechaMensaje: hoy.toISOString(), fechaRecepcion: hoy.toISOString(), identificado: false, odontologoId: null, odontologoNombre: null,
        remitenteTipo: null, remitenteEtiqueta: null, remitenteVerificado: true,
        motivoCuarentena: 'Remitente desconocido: su dirección no está en la ficha de ningún odontólogo ni fue autorizada.',
        sugerencia: { paciente: null, trabajo: null, catalogoTrabajoId: null, fechaEntrega: null, prioridad: 'NORMAL', precio: null, observaciones: 'Buenas tardes, quería consultarles cuánto sale aproximadamente un puente de 3 piezas. Gracias!' },
        notaAdjuntos: 'plano.pdf: no se guardó porque el remitente todavía no está autorizado', pedidoId: null, motivoDescarte: null, resueltoPor: null, fechaResolucion: null, adjuntos: [],
      },
      {
        id: 3, canal: 'EMAIL', estado: 'SIN_AUTORIZAR', remitenteEmail: 'perez@clinica.com', remitenteNombre: 'Dr. Carlos Pérez',
        asunto: 'Urgente: cambiar los datos de pago', cuerpo: 'Necesito que me transfieran lo que me deben a una cuenta nueva. Pasen el CBU por acá.',
        fechaMensaje: hoy.toISOString(), fechaRecepcion: hoy.toISOString(), identificado: true, odontologoId: 1, odontologoNombre: 'Dr. Carlos Pérez',
        remitenteTipo: 'ODONTOLOGO', remitenteEtiqueta: 'Dr. Carlos Pérez', remitenteVerificado: false,
        motivoCuarentena: 'La dirección es conocida, pero el servidor de correo no pudo comprobar que el mail venga realmente de ella (podría ser alguien haciéndose pasar por esa persona).',
        sugerencia: { paciente: null, trabajo: null, catalogoTrabajoId: null, fechaEntrega: null, prioridad: 'NORMAL', precio: null, observaciones: null },
        notaAdjuntos: null, pedidoId: null, motivoDescarte: null, resueltoPor: null, fechaResolucion: null, adjuntos: [],
      },
    ];
  }
}
