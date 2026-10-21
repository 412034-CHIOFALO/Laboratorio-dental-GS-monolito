import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { AdjuntoSolicitud, EstadoCorreo, EstadoSolicitud, Solicitud, SolicitudesService } from '../../../services/solicitudes.service';
import { OdontologoResponse, OdontologosService } from '../../../services/odontologos.service';
import { CatalogoService, TipoTrabajoResponse } from '../../../services/catalogo.service';
import { PedidoRequest, Prioridad } from '../../../services/pedidos.service';
import { AuthService } from '../../../services/auth';
import { NotificationService } from '../../../services/notification.service';
import { comoLocalDate } from '../../../services/date-utils';

/**
 * Pedidos que llegaron por mail y esperan que alguien los revise. El sistema adelanta el formulario con lo que pudo
 * leer del texto (paciente, trabajo, fecha, urgencia, precio) y reconoce al odontólogo por su email; una persona confirma
 * y recién ahí se crea el pedido, o descarta la solicitud (consultas, spam).
 */
@Component({
  selector: 'app-solicitudes',
  imports: [ReactiveFormsModule, DatePipe],
  templateUrl: './solicitudes.html',
  styleUrl: './solicitudes.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SolicitudesComponent implements OnInit {
  private readonly svc = inject(SolicitudesService);
  private readonly odontologosSvc = inject(OdontologosService);
  private readonly catalogoSvc = inject(CatalogoService);
  private readonly auth = inject(AuthService);
  private readonly notif = inject(NotificationService);

  readonly estados: { valor: EstadoSolicitud; etiqueta: string }[] = [
    { valor: 'NUEVA', etiqueta: 'Por revisar' },
    { valor: 'CONVERTIDA', etiqueta: 'Convertidas' },
    { valor: 'DESCARTADA', etiqueta: 'Descartadas' },
  ];

  readonly estado = signal<EstadoSolicitud>('NUEVA');
  readonly lista = signal<Solicitud[]>([]);
  readonly cargando = signal(false);
  readonly seleccionada = signal<Solicitud | null>(null);
  readonly correo = signal<EstadoCorreo | null>(null);
  readonly odontologos = signal<OdontologoResponse[]>([]);
  readonly trabajos = signal<TipoTrabajoResponse[]>([]);
  readonly guardando = signal(false);
  readonly descartando = signal(false);
  readonly confirmandoDescarte = signal(false);

  /** Convertir y descartar: solo ADMIN y ADMINISTRATIVO (el técnico puede mirar). */
  puedeResolver(): boolean {
    // Un método y no un computed: el rol sale de la sesión, que no es una señal. Un computed lo leería una sola vez,
    // antes de que termine de cargarse la sesión, y se quedaría con ese valor para siempre.
    return this.auth.puedeVerFinanzas();
  }
  readonly minimaEntrega = comoLocalDate(new Date(Date.now() + 86_400_000));

  readonly form = new FormGroup({
    odontologoId: new FormControl<number | null>(null),
    odontologoNombre: new FormControl('', { nonNullable: true }),
    paciente: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(150)] }),
    catalogoTrabajoId: new FormControl<number | null>(null),
    trabajo: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(200)] }),
    fechaEntrega: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    prioridad: new FormControl<Prioridad>('NORMAL', { nonNullable: true }),
    precio: new FormControl<number | null>(null, [Validators.min(0)]),
    observaciones: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(900)] }),
  });
  readonly motivoDescarte = new FormControl('', { nonNullable: true, validators: [Validators.maxLength(255)] });

  ngOnInit(): void {
    this.cargar();
    this.svc.estadoCorreo().subscribe({ next: e => this.correo.set(e), error: () => this.correo.set(null) });
    this.odontologosSvc.buscar().subscribe({ next: o => this.odontologos.set(o), error: () => {} });
    this.catalogoSvc.listar().subscribe({ next: t => this.trabajos.set(t.filter(x => x.activo)), error: () => {} });
  }

  cargar(): void {
    this.cargando.set(true);
    this.svc.listar(this.estado()).subscribe({
      next: l => {
        this.lista.set(l);
        this.cargando.set(false);
        const actual = this.seleccionada();
        const sigue = actual ? l.find(s => s.id === actual.id) : null;
        this.seleccionar(sigue ?? l[0] ?? null);
      },
      error: err => { this.cargando.set(false); this.notif.errorHttp(err, 'No se pudieron cargar las solicitudes'); },
    });
  }

  cambiarEstado(e: EstadoSolicitud): void {
    if (e === this.estado()) return;
    this.estado.set(e);
    this.seleccionada.set(null);
    this.cargar();
  }

  /** Abre una solicitud y adelanta el formulario con lo que el sistema pudo leer. */
  seleccionar(s: Solicitud | null): void {
    this.seleccionada.set(s);
    this.confirmandoDescarte.set(false);
    this.motivoDescarte.reset('');
    if (!s) return;
    const g = s.sugerencia;
    this.form.reset({
      odontologoId: s.odontologoId,
      odontologoNombre: s.odontologoNombre ?? s.remitenteNombre ?? '',
      paciente: g.paciente ?? '',
      catalogoTrabajoId: g.catalogoTrabajoId,
      trabajo: g.trabajo ?? '',
      fechaEntrega: g.fechaEntrega ?? comoLocalDate(new Date(Date.now() + 7 * 86_400_000)),
      prioridad: g.prioridad ?? 'NORMAL',
      precio: g.precio,
      observaciones: g.observaciones ?? '',
    });
  }

  /** Al elegir un trabajo del catálogo se completa su nombre (y el precio, si todavía no hay uno). */
  alElegirTrabajo(): void {
    const id = this.form.controls.catalogoTrabajoId.value;
    const t = this.trabajos().find(x => x.id === Number(id));
    if (!t) { this.form.controls.catalogoTrabajoId.setValue(null); return; }
    this.form.controls.catalogoTrabajoId.setValue(t.id);
    this.form.controls.trabajo.setValue(t.nombre);
    if (this.form.controls.precio.value == null) this.form.controls.precio.setValue(t.precio);
  }

  /** Al elegir un odontólogo de la lista se usa su ID; si se deja vacío se usa el nombre escrito (se crea si no existe). */
  alElegirOdontologo(): void {
    const id = this.form.controls.odontologoId.value;
    const o = this.odontologos().find(x => x.id === Number(id));
    if (o) {
      this.form.controls.odontologoId.setValue(o.id);
      this.form.controls.odontologoNombre.setValue(o.nombre);
    } else {
      this.form.controls.odontologoId.setValue(null);
    }
  }

  get formularioValido(): boolean {
    const v = this.form.getRawValue();
    return this.form.valid && (v.odontologoId != null || v.odontologoNombre.trim().length > 0) && v.fechaEntrega >= this.minimaEntrega;
  }

  crearPedido(): void {
    const s = this.seleccionada();
    if (!s || !this.formularioValido || this.guardando()) return;
    const v = this.form.getRawValue();
    const pedido: PedidoRequest = {
      odontologoId: v.odontologoId,
      odontologoNombre: v.odontologoNombre.trim(),
      paciente: v.paciente.trim(),
      catalogoTrabajoId: v.catalogoTrabajoId,
      trabajo: v.trabajo.trim(),
      fechaEntrega: v.fechaEntrega,
      prioridad: v.prioridad,
      precioAcordado: v.precio,
      observaciones: v.observaciones.trim() || null,
    };
    this.guardando.set(true);
    this.svc.convertir(s.id, pedido).subscribe({
      next: p => {
        this.guardando.set(false);
        this.notif.exito(`Pedido ${p.nroPedido} creado`);
        this.quitar(s.id);
      },
      error: err => { this.guardando.set(false); this.notif.errorHttp(err, 'No se pudo crear el pedido'); },
    });
  }

  descartar(): void {
    const s = this.seleccionada();
    if (!s || this.descartando()) return;
    this.descartando.set(true);
    this.svc.descartar(s.id, this.motivoDescarte.value.trim() || null).subscribe({
      next: () => { this.descartando.set(false); this.notif.alerta('Solicitud descartada'); this.quitar(s.id); },
      error: err => { this.descartando.set(false); this.notif.errorHttp(err, 'No se pudo descartar'); },
    });
  }

  private quitar(id: number): void {
    const resto = this.lista().filter(x => x.id !== id);
    this.lista.set(resto);
    this.seleccionar(resto[0] ?? null);
  }

  descargar(s: Solicitud, a: AdjuntoSolicitud): void {
    this.svc.descargarAdjunto(s.id, a.id).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        if (a.tipo === 'DOCUMENTO') {
          window.open(url, '_blank');
        } else {
          const enlace = document.createElement('a');
          enlace.href = url;
          enlace.download = a.fileName;
          enlace.click();
        }
        setTimeout(() => URL.revokeObjectURL(url), 15_000);
      },
      error: err => this.notif.errorHttp(err, 'No se pudo descargar el archivo'),
    });
  }

  // ── Presentación ──────────────────────────────────────────────────

  tamanio(bytes: number | null): string {
    if (bytes == null) return '';
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
    return `${(bytes / 1024 / 1024).toLocaleString('es-AR', { maximumFractionDigits: 1 })} MB`;
  }

  detalleMalla(a: AdjuntoSolicitud): string {
    if (!a.formato) return '';
    const partes = [a.formato.replace('_', ' ').toLowerCase().replace('stl', 'STL').replace('ply', 'PLY').replace('obj', 'OBJ').replace('ascii', 'ASCII')];
    if (a.triangulos != null) partes.push(`${a.triangulos.toLocaleString('es-AR')} triángulos`);
    if (a.dimXMm != null && a.dimYMm != null && a.dimZMm != null) {
      const m = (n: number) => n.toLocaleString('es-AR', { maximumFractionDigits: 1 });
      partes.push(`${m(a.dimXMm)} × ${m(a.dimYMm)} × ${m(a.dimZMm)} mm`);
    }
    return partes.join(' · ');
  }

  resumen(s: Solicitud): string {
    const c = s.sugerencia;
    return [c.trabajo, c.paciente ? `paciente ${c.paciente}` : null].filter(Boolean).join(' · ') || 'Sin datos reconocidos';
  }
}
