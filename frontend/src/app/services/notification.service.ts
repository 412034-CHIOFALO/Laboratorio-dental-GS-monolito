import { Injectable, inject } from '@angular/core';
import { ToastrService } from 'ngx-toastr';

/**
 * Estructura estándar del cuerpo de error que devuelve el backend Spring
 * (ver GlobalExceptionHandler + ErrorResponse en cada MS).
 *
 *   {
 *     "timestamp": "...",
 *     "status": 400,
 *     "error": "Bad Request",
 *     "mensaje": "Errores de validación en la petición",
 *     "ruta": "/...",
 *     "campos": [                          ← presente en errores 400 de validación
 *       { "campo": "precio", "mensaje": "El precio no puede ser negativo" }
 *     ]
 *   }
 */
interface BackendErrorBody {
  timestamp?: string;
  status?: number;
  error?: string;
  mensaje?: string;
  message?: string;
  ruta?: string;
  campos?: Array<{ campo: string; mensaje: string }>;
}

/**
 * Wrapper sobre ngx-toastr para mantener un API consistente y
 * facilitar reemplazar la librería en el futuro sin tocar componentes.
 *
 * Uso desde componentes:
 *   private notif = inject(NotificationService);
 *   this.notif.exito('Pedido guardado');
 *   this.notif.error('No se pudo conectar al servidor');
 *
 * Uso para errores HTTP típicos:
 *   error: err => this.notif.errorHttp(err, 'No se pudo guardar el pedido')
 */
@Injectable({ providedIn: 'root' })
export class NotificationService {

  private toastr = inject(ToastrService);

  /** Operación exitosa (verde). Default 3.5s. */
  exito(mensaje: string, titulo?: string): void {
    this.toastr.success(mensaje, titulo);
  }

  /** Error (rojo). 5s para que el usuario tenga tiempo de leer. */
  error(mensaje: string, titulo = 'Error'): void {
    this.toastr.error(mensaje, titulo, { timeOut: 5000, enableHtml: true });
  }

  /** Información (azul). */
  info(mensaje: string, titulo?: string): void {
    this.toastr.info(mensaje, titulo);
  }

  /** Advertencia (ámbar). */
  alerta(mensaje: string, titulo = 'Atención'): void {
    this.toastr.warning(mensaje, titulo);
  }

  /**
   * Extrae el mensaje de error de una respuesta HTTP típica del backend.
   *
   *  - **Validaciones (400 con `campos[]`)** → muestra cada campo con su mensaje
   *  - **Errores de negocio (4xx con `mensaje`)** → muestra el mensaje del back
   *  - **Sin conexión / 0 / network** → mensaje de "no se pudo conectar"
   *  - **Cualquier otro** → usa el `fallback`
   *
   * El primer parámetro acepta tanto `HttpErrorResponse` como cualquier objeto
   * que tenga `{ error: BackendErrorBody, status, message }`.
   */
  errorHttp(err: any, fallback = 'Ocurrió un error inesperado'): void {
    const body = (err?.error ?? {}) as BackendErrorBody;
    const status: number | undefined = err?.status;

    // ── Caso 1: sin conexión / CORS / network down ──────────────────
    if (status === 0 || status === undefined) {
      this.toastr.error(
        'No se pudo conectar con el servidor. Verificá que el microservicio esté corriendo.',
        'Sin conexión',
        { timeOut: 6000 }
      );
      return;
    }

    // ── Caso 2: validación 400 con campos detallados ────────────────
    if (status === 400 && Array.isArray(body.campos) && body.campos.length > 0) {
      // Listado bonito con cada campo y su mensaje (HTML para que se vea formateado)
      const detalle = body.campos
        .map(c => `<b>${this.humanizarCampo(c.campo)}:</b> ${c.mensaje}`)
        .join('<br>');
      this.toastr.error(detalle, 'Datos inválidos', {
        timeOut: 6000,
        enableHtml: true,
      });
      return;
    }

    // ── Caso 3: gateway o microservicio caído (502/503/504), sin body propio ──
    if (status === 502 || status === 503 || status === 504) {
      this.toastr.error(
        'El servidor no está disponible en este momento. Probá de nuevo en unos segundos.',
        'Servicio no disponible',
        { timeOut: 6000 }
      );
      return;
    }

    // ── Caso 4: mensaje del backend (BusinessException, ConflictException, etc.) ──
    // Nunca usamos err.message: es la frase técnica cruda de Angular
    // ("Http failure response for http://...: 502 Bad Gateway"), no algo para mostrar.
    const msg = body.mensaje ?? body.message ?? fallback;
    const titulo = body.error
      ?? this.tituloPorStatus(status)
      ?? `Error ${status}`;
    this.toastr.error(msg, titulo, { timeOut: 5000 });
  }

  // ── Helpers privados ─────────────────────────────────────────────

  private tituloPorStatus(status: number): string | null {
    switch (status) {
      case 401: return 'Sesión expirada';
      case 403: return 'Sin permisos';
      case 404: return 'No encontrado';
      case 409: return 'Conflicto';
      case 422: return 'Operación no permitida';
      case 500: return 'Error del servidor';
      default:  return null;
    }
  }

  /** Convierte un nombre de campo del DTO a algo legible en castellano. */
  private humanizarCampo(campo: string): string {
    const map: Record<string, string> = {
      nombre: 'Nombre',
      apellido: 'Apellido',
      username: 'Usuario',
      password: 'Contraseña',
      descripcion: 'Descripción',
      precio: 'Precio',
      precioAcordado: 'Precio acordado',
      precioUnitario: 'Precio unitario',
      categoria: 'Categoría',
      tiempoEstimadoDias: 'Tiempo estimado',
      cantidad: 'Cantidad',
      unidad: 'Unidad',
      materialId: 'Material',
      materialNombre: 'Material',
      paciente: 'Paciente',
      odontologoId: 'Odontólogo',
      odontologoNombre: 'Odontólogo',
      trabajo: 'Trabajo',
      fechaEntrega: 'Fecha de entrega',
      fechaEntregaReal: 'Fecha de entrega',
      fechaVencimiento: 'Fecha de vencimiento',
      dni: 'DNI',
      cuit: 'CUIT',
      email: 'Email',
      telefono: 'Teléfono',
      receptorTelefono: 'Teléfono del receptor',
      receptorNombre: 'Receptor',
      cargadoPorTelefono: 'Teléfono de quien cargó',
      matricula: 'Matrícula',
      clinica: 'Clínica',
      direccion: 'Dirección',
      stockMinimo: 'Stock mínimo',
      stockActual: 'Stock actual',
      unidadMedida: 'Unidad de medida',
      proveedor: 'Proveedor',
      motivo: 'Motivo',
      monto: 'Monto',
      montoBase: 'Monto base',
      concepto: 'Concepto',
      referencia: 'Referencia',
      nota: 'Nota',
      observaciones: 'Observaciones',
      observacionesEntrega: 'Observaciones de entrega',
      retiradoPor: 'Recibido por',
      nroPedido: 'N° de pedido',
      nroFacturaProveedor: 'N° de factura',
      frecuencia: 'Frecuencia',
      medio: 'Medio de pago',
      adminWhatsappPhone: 'WhatsApp del administrador',
      fotoUrl: 'Foto',
    };
    return map[campo] ?? campo.charAt(0).toUpperCase() + campo.slice(1);
  }
}
