import { ChangeDetectionStrategy, Component, ElementRef, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import {
  AdjuntoSolicitud, CantidadesSolicitudes, EstadoCorreo, EstadoSolicitud, Remitente, RemitenteTipo, Solicitud, SolicitudesService,
} from '../../../services/solicitudes.service';
import { OdontologoResponse, OdontologosService } from '../../../services/odontologos.service';
import { CatalogoService, TipoTrabajoResponse } from '../../../services/catalogo.service';
import { PedidoRequest, Prioridad } from '../../../services/pedidos.service';
import { AuthService } from '../../../services/auth';
import { NotificationService } from '../../../services/notification.service';
import { comoLocalDate } from '../../../services/date-utils';
import { PageHeaderComponent } from '../../../shared/page-header';
import { SectionTabsComponent, SeccionTab } from '../../../shared/section-tabs';
import { describirMalla, describirTamanio } from '../../../shared/malla.util';
import { Visor3dComponent } from '../escaneos/visor3d.component';
import { Faltante, enumerar, faltantesDelPedido } from './solicitudes.validacion';

/** Formatos de escaneo que el visor 3D sabe mostrar. */
const VISUALIZABLES_3D = ['.stl', '.obj', '.ply'];

/** Valor del selector de trabajo para escribirlo a mano cuando no está en el catálogo. */
const OTRO_TRABAJO = 'OTRO';

/** Lo que se puede mirar en la pantalla: una bandeja de mails o la lista de direcciones autorizadas. */
type Vista = EstadoSolicitud | 'AUTORIZADOS';

/**
 * Pedidos que llegaron por mail y esperan que alguien los revise. El sistema adelanta el formulario con lo que pudo
 * leer del texto (paciente, trabajo, fecha, urgencia, precio); una persona confirma y recién ahí se crea el pedido, o
 * descarta la solicitud (consultas, spam).
 *
 * Solo entran directo a «Por revisar» los mails de remitentes conocidos y verificados. Los de cualquier otra dirección
 * quedan aparte en «Remitentes nuevos», sin adjuntos, hasta que una persona dice quién es (odontólogo, secretaria u
 * otro) o los bloquea.
 */
@Component({
  selector: 'app-solicitudes',
  imports: [ReactiveFormsModule, DatePipe, PageHeaderComponent, SectionTabsComponent, Visor3dComponent],
  templateUrl: './solicitudes.html',
  styleUrl: './solicitudes.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SolicitudesComponent implements OnInit, OnDestroy {
  private readonly svc = inject(SolicitudesService);
  private readonly odontologosSvc = inject(OdontologosService);
  private readonly catalogoSvc = inject(CatalogoService);
  private readonly auth = inject(AuthService);
  private readonly notif = inject(NotificationService);
  private readonly ruta = inject(ActivatedRoute);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** Las pestañas llevan el numerito de lo que espera a alguien (pedidos por revisar, remitentes nuevos). */
  readonly pestanias = computed<SeccionTab[]>(() => [
    { id: 'NUEVA', etiqueta: 'Por revisar', cantidad: this.cantidades().nuevas },
    { id: 'SIN_AUTORIZAR', etiqueta: 'Remitentes nuevos', cantidad: this.cantidades().sinAutorizar, aviso: true },
    { id: 'CONVERTIDA', etiqueta: 'Convertidas' },
    { id: 'DESCARTADA', etiqueta: 'Descartadas' },
    { id: 'AUTORIZADOS', etiqueta: 'Direcciones autorizadas' },
  ]);

  readonly tiposRemitente: { valor: RemitenteTipo; etiqueta: string; corta: string; ayuda: string }[] = [
    { valor: 'ODONTOLOGO', etiqueta: 'Es el odontólogo', corta: 'Odontólogo', ayuda: 'Escribe él mismo, desde otra casilla o por primera vez.' },
    { valor: 'SECRETARIA', etiqueta: 'Es su secretaria o asistente', corta: 'Secretaria o asistente', ayuda: 'Escribe en nombre de un odontólogo.' },
    { valor: 'OTRO', etiqueta: 'Es otra persona', corta: 'Otra persona', ayuda: 'Un contador, un proveedor… Se acepta igual y no queda atada a ningún odontólogo.' },
  ];

  readonly estado = signal<Vista>('NUEVA');
  readonly cantidades = signal<CantidadesSolicitudes>({ nuevas: 0, sinAutorizar: 0 });
  readonly lista = signal<Solicitud[]>([]);
  readonly cargando = signal(false);
  readonly seleccionada = signal<Solicitud | null>(null);
  readonly correo = signal<EstadoCorreo | null>(null);
  readonly odontologos = signal<OdontologoResponse[]>([]);
  readonly trabajos = signal<TipoTrabajoResponse[]>([]);
  readonly guardando = signal(false);
  /** Se tocó «Crear pedido» con datos que faltan: recién ahí los campos se marcan en rojo, no antes de que la persona escriba. */
  readonly intentoCrear = signal(false);
  readonly descartando = signal(false);
  readonly confirmandoDescarte = signal(false);
  readonly aceptando = signal(false);
  readonly bloqueando = signal(false);
  readonly confirmandoBloqueo = signal(false);
  readonly remitentes = signal<Remitente[]>([]);
  readonly cargandoRemitentes = signal(false);
  readonly agregando = signal(false);
  readonly confirmandoQuitar = signal<number | null>(null);
  readonly cargandoAdjunto = signal<number | null>(null);
  readonly visor = signal<{ url: string; nombre: string } | null>(null);
  private urlVisor: string | null = null;

  /** Convertir y descartar: solo ADMIN y ADMINISTRATIVO (el técnico puede mirar). */
  puedeResolver(): boolean {
    // Un método y no un computed: el rol sale de la sesión, que no es una señal. Un computed lo leería una sola vez,
    // antes de que termine de cargarse la sesión, y se quedaría con ese valor para siempre.
    return this.auth.puedeVerFinanzas();
  }

  /** Quitar una dirección de la lista: solo Administración (los demás la ven pero no la tocan). */
  puedeQuitarRemitentes(): boolean {
    return this.auth.isAdmin();
  }

  readonly minimaEntrega = comoLocalDate(new Date(Date.now() + 86_400_000));

  readonly otroTrabajo = OTRO_TRABAJO;

  /**
   * Lo que se eligió en el selector de trabajo: uno del catálogo, «otro» (se escribe) o nada. Es solo de pantalla: lo que
   * viaja en el pedido son `catalogoTrabajoId` y `trabajo`, y así el nombre del trabajo se muestra una sola vez.
   */
  readonly eleccionTrabajo = new FormControl<number | typeof OTRO_TRABAJO | null>(null);

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

  /** Qué hacer con un mail de un remitente sin autorizar: quién es, y si se recuerda su dirección. */
  readonly formAceptar = new FormGroup({
    tipo: new FormControl<RemitenteTipo | null>(null, [Validators.required]),
    odontologoId: new FormControl<number | null>(null),
    aclaracion: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(100)] }),
    recordar: new FormControl(true, { nonNullable: true }),
    /** true = el odontólogo no está en la lista: se lo carga ahora con estos datos. */
    esNuevo: new FormControl(false, { nonNullable: true }),
    nuevoNombre: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(150)] }),
    nuevaClinica: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] }),
    nuevoTelefono: new FormControl('', { nonNullable: true, validators: [Validators.pattern(/^[0-9+()\-\s]{6,30}$|^$/)] }),
  });

  /** Alta a mano de una dirección autorizada (por ejemplo, la secretaria, antes de que escriba). */
  readonly formRemitente = new FormGroup({
    email: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.email, Validators.maxLength(150)] }),
    tipo: new FormControl<RemitenteTipo>('SECRETARIA', { nonNullable: true }),
    odontologoId: new FormControl<number | null>(null),
    aclaracion: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(100)] }),
  });

  ngOnInit(): void {
    if (this.ruta.snapshot.queryParamMap.get('ver') === 'sin-autorizar') this.estado.set('SIN_AUTORIZAR');
    this.cargar();
    this.refrescarCantidades();
    this.svc.estadoCorreo().subscribe({ next: e => this.correo.set(e), error: () => this.correo.set(null) });
    // Sin estas listas los selectores del formulario quedan vacíos: hay que decirlo, no dejar que parezca que no hay datos.
    this.odontologosSvc.buscar().subscribe({
      next: o => this.odontologos.set(o),
      error: err => this.notif.errorHttp(err, 'No se pudo cargar la lista de odontólogos'),
    });
    this.catalogoSvc.listar().subscribe({
      next: t => {
        this.trabajos.set(t.filter(x => x.activo));
        this.completarPrecioDelCatalogo();
      },
      error: err => this.notif.errorHttp(err, 'No se pudo cargar el catálogo de trabajos'),
    });
  }

  private refrescarCantidades(): void {
    this.svc.cantidades().subscribe({ next: c => this.cantidades.set(c), error: () => { /* es solo el número de las pestañas */ } });
  }

  cargar(): void {
    const vista = this.estado();
    if (vista === 'AUTORIZADOS') { this.cargarRemitentes(); return; }
    this.cargando.set(true);
    this.svc.listar(vista).subscribe({
      next: l => {
        this.lista.set(l);
        this.cargando.set(false);
        const actual = this.seleccionada();
        const sigue = actual ? l.find(s => s.id === actual.id) : null;
        this.seleccionar(sigue ?? l[0] ?? null);
      },
      error: err => { this.cargando.set(false); this.notif.errorHttp(err, 'No se pudieron cargar los mails'); },
    });
  }

  cambiarEstado(e: Vista): void {
    if (e === this.estado()) return;
    this.estado.set(e);
    this.seleccionada.set(null);
    this.cargar();
  }

  /** Lo que avisa la barra de pestañas llega como texto: acá se vuelve una vista conocida. */
  elegirVista(id: string): void {
    if (this.pestanias().some(p => p.id === id)) this.cambiarEstado(id as Vista);
  }

  /**
   * Un toque en la lista abre el mail. En el celular el detalle queda debajo de la lista, así que se lo trae a la vista
   * (si no, parece que no pasó nada).
   */
  abrir(s: Solicitud): void {
    this.seleccionar(s);
    if (window.innerWidth <= 860) {
      setTimeout(() => this.host.nativeElement.querySelector('.sol-detalle')?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
    }
  }

  /** Abre una solicitud y adelanta el formulario con lo que el sistema pudo leer. */
  seleccionar(s: Solicitud | null): void {
    this.seleccionada.set(s);
    this.confirmandoDescarte.set(false);
    this.confirmandoBloqueo.set(false);
    this.intentoCrear.set(false);
    this.motivoDescarte.reset('');
    // Si no se pudo verificar el mail no se ofrece recordar la dirección: se acepta este mail y nada más.
    this.formAceptar.reset({
      tipo: s?.remitenteTipo ?? null, odontologoId: s?.odontologoId ?? null, aclaracion: '', recordar: s?.remitenteVerificado !== false,
      esNuevo: false, nuevoNombre: this.nombreSugerido(s), nuevaClinica: '', nuevoTelefono: '',
    });
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
    this.eleccionTrabajo.reset(g.catalogoTrabajoId ?? (g.trabajo ? OTRO_TRABAJO : null));
    this.completarPrecioDelCatalogo();
  }

  /**
   * Si el mail nombra un trabajo del catálogo pero no dice un precio, se propone el de lista, igual que cuando se elige el
   * trabajo a mano (antes quedaba vacío y parecía que el sistema no lo conocía). Nunca pisa un precio que ya está cargado.
   */
  private completarPrecioDelCatalogo(): void {
    const c = this.form.controls;
    const id = c.catalogoTrabajoId.value;
    if (id == null || c.precio.value != null) return;
    const t = this.trabajos().find(x => x.id === id);
    if (t?.precio) c.precio.setValue(t.precio);
  }

  /**
   * Al elegir un trabajo del catálogo se completa su nombre (y el precio, si todavía no hay uno). Con «otro» se deja
   * escribir; si venía de un trabajo del catálogo se vacía el nombre, para que no quede el anterior por error.
   */
  alElegirTrabajo(): void {
    const eleccion = this.eleccionTrabajo.value;
    const c = this.form.controls;
    const t = typeof eleccion === 'number' ? this.trabajos().find(x => x.id === eleccion) : undefined;
    if (t) {
      c.catalogoTrabajoId.setValue(t.id);
      c.trabajo.setValue(t.nombre);
      if (c.precio.value == null) c.precio.setValue(t.precio);
      return;
    }
    if (c.catalogoTrabajoId.value != null) c.trabajo.setValue('');
    c.catalogoTrabajoId.setValue(null);
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

  /** Lo que impide crear el pedido ahora mismo, con las mismas reglas que valida el servidor. Vacío = se puede crear. */
  faltantes(): Faltante[] {
    const v = this.form.getRawValue();
    return faltantesDelPedido({
      odontologoId: v.odontologoId, odontologoNombre: v.odontologoNombre, paciente: v.paciente, trabajo: v.trabajo,
      eligioOtroTrabajo: this.eleccionTrabajo.value === OTRO_TRABAJO, fechaEntrega: v.fechaEntrega, precio: v.precio,
    }, this.minimaEntrega);
  }

  /** «el paciente y la fecha de entrega», para el aviso de arriba del botón. */
  resumenFaltantes(): string {
    return enumerar(this.faltantes().map(f => f.resumen));
  }

  /** El mensaje que va debajo de un campo, solo después de haber tocado «Crear pedido». */
  errorDe(campoId: string): string | null {
    if (!this.intentoCrear()) return null;
    return this.faltantes().find(f => f.campoId === campoId)?.mensaje ?? null;
  }

  get formularioValido(): boolean {
    return this.form.valid && this.faltantes().length === 0;
  }

  /** Lleva el cursor al primer dato que falta (en el celular el formulario es largo: si no, no se ve qué pasó). */
  private enfocar(campoId: string | undefined): void {
    if (!campoId) return;
    const ids = campoId === 'odontologo' ? ['odontologo-nombre', 'odontologo'] : [campoId];
    setTimeout(() => {
      const el = ids.map(id => this.host.nativeElement.querySelector<HTMLElement>('#' + id)).find(e => e);
      // Sin desplazamiento suave: queda a medias cuando el formulario cambia de alto (aparecen los mensajes) y deja el campo
      // tapado por la barra de arriba.
      el?.scrollIntoView({ block: 'center' });
      el?.focus({ preventScroll: true });
    });
  }

  crearPedido(): void {
    const s = this.seleccionada();
    if (!s || this.guardando()) return;
    if (!this.formularioValido) {
      // El botón no se desactiva en silencio: se explica qué falta, se marcan los campos y se lleva el foco al primero.
      this.intentoCrear.set(true);
      this.form.markAllAsTouched();
      const faltan = this.faltantes();
      if (faltan.length === 0) {
        this.notif.alerta('Hay un dato del formulario que no es válido. Revisá los campos.', 'No se puede crear el pedido');
      }
      this.enfocar(faltan[0]?.campoId);
      return;
    }
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
      next: () => { this.descartando.set(false); this.notif.alerta('Mail descartado'); this.quitar(s.id); },
      error: err => { this.descartando.set(false); this.notif.errorHttp(err, 'No se pudo descartar'); },
    });
  }

  private quitar(id: number): void {
    const resto = this.lista().filter(x => x.id !== id);
    this.lista.set(resto);
    this.seleccionar(resto[0] ?? null);
    this.refrescarCantidades();
  }

  // ── Remitentes nuevos ─────────────────────────────────────────────

  get puedeAceptar(): boolean {
    const v = this.formAceptar.getRawValue();
    if (v.tipo == null || !this.formAceptar.valid) return false;
    if (v.tipo === 'OTRO') return true;
    return v.esNuevo ? v.nuevoNombre.trim().length > 0 : v.odontologoId != null;
  }

  /** El nombre con el que escribió el remitente (sin comillas ni el mail), para no tener que tipearlo. */
  private nombreSugerido(s: Solicitud | null): string {
    const n = (s?.remitenteNombre ?? '').replace(/["<>]/g, '').trim();
    return n.includes('@') ? '' : n;
  }

  /** Cambiar entre «elegir de la lista» y «es nuevo». */
  alternarNuevo(): void {
    const c = this.formAceptar.controls;
    c.esNuevo.setValue(!c.esNuevo.value);
    if (c.esNuevo.value) c.odontologoId.setValue(null);
  }

  aceptar(): void {
    const s = this.seleccionada();
    if (!s || !this.puedeAceptar || this.aceptando()) return;
    const v = this.formAceptar.getRawValue();
    const recordar = v.recordar && s.remitenteVerificado !== false;
    this.aceptando.set(true);
    this.svc.aceptar(s.id, {
      tipo: v.tipo as RemitenteTipo,
      odontologoId: v.tipo === 'OTRO' || v.esNuevo ? null : v.odontologoId,
      aclaracion: v.aclaracion.trim() || null,
      recordar,
      nuevoOdontologo: v.tipo !== 'OTRO' && v.esNuevo
        ? { nombre: v.nuevoNombre.trim(), clinica: v.nuevaClinica.trim() || null, telefono: v.nuevoTelefono.trim() || null }
        : null,
    }).subscribe({
      next: () => {
        this.aceptando.set(false);
        if (v.tipo !== 'OTRO' && v.esNuevo) this.recargarOdontologos();
        this.notif.exito(recordar
          ? 'Mail aceptado. Los próximos de esa dirección entran directo a «Por revisar»'
          : 'Mail aceptado: ya está en «Por revisar»');
        this.quitar(s.id);
      },
      error: err => { this.aceptando.set(false); this.notif.errorHttp(err, 'No se pudo aceptar el mail'); },
    });
  }

  /** Después de dar de alta a un odontólogo hay que volver a pedir la lista: si no, el formulario del pedido no lo ofrece. */
  private recargarOdontologos(): void {
    this.odontologosSvc.buscar().subscribe({ next: o => this.odontologos.set(o), error: () => { /* se vuelve a pedir al recargar */ } });
  }

  bloquear(): void {
    const s = this.seleccionada();
    if (!s || this.bloqueando()) return;
    this.bloqueando.set(true);
    this.svc.bloquearRemitente(s.id).subscribe({
      next: () => {
        this.bloqueando.set(false);
        this.confirmandoBloqueo.set(false);
        this.notif.alerta(`Se bloqueó a ${s.remitenteEmail}: sus mails ya no llegan`);
        this.cargar();
        this.refrescarCantidades();
      },
      error: err => { this.bloqueando.set(false); this.notif.errorHttp(err, 'No se pudo bloquear al remitente'); },
    });
  }

  /** Bloquear solo tiene sentido para direcciones que no son de un odontólogo: el sistema no deja bloquear a un cliente. */
  puedeBloquear(s: Solicitud): boolean {
    return !!s.remitenteEmail && s.odontologoId == null;
  }

  // ── Direcciones autorizadas ───────────────────────────────────────

  private cargarRemitentes(): void {
    this.cargandoRemitentes.set(true);
    this.svc.remitentes().subscribe({
      next: r => { this.remitentes.set(r); this.cargandoRemitentes.set(false); },
      error: err => { this.cargandoRemitentes.set(false); this.notif.errorHttp(err, 'No se pudo cargar la lista de direcciones'); },
    });
  }

  autorizadas(): Remitente[] { return this.remitentes().filter(r => r.estado === 'AUTORIZADO'); }
  bloqueadas(): Remitente[] { return this.remitentes().filter(r => r.estado === 'BLOQUEADO'); }

  get puedeAgregarRemitente(): boolean {
    const v = this.formRemitente.getRawValue();
    return this.formRemitente.valid && (v.tipo === 'OTRO' || v.odontologoId != null);
  }

  agregarRemitente(): void {
    if (!this.puedeAgregarRemitente || this.agregando()) return;
    const v = this.formRemitente.getRawValue();
    this.agregando.set(true);
    this.svc.autorizarRemitente({
      email: v.email.trim(),
      tipo: v.tipo,
      odontologoId: v.tipo === 'OTRO' ? null : v.odontologoId,
      aclaracion: v.aclaracion.trim() || null,
    }).subscribe({
      next: () => {
        this.agregando.set(false);
        this.notif.exito('Dirección autorizada');
        this.formRemitente.reset({ email: '', tipo: 'SECRETARIA', odontologoId: null, aclaracion: '' });
        this.cargarRemitentes();
      },
      error: err => { this.agregando.set(false); this.notif.errorHttp(err, 'No se pudo autorizar la dirección'); },
    });
  }

  quitarRemitente(r: Remitente): void {
    this.svc.quitarRemitente(r.id).subscribe({
      next: () => {
        this.confirmandoQuitar.set(null);
        this.notif.exito(r.estado === 'BLOQUEADO' ? 'Dirección desbloqueada' : 'Dirección quitada de las autorizadas');
        this.cargarRemitentes();
      },
      error: err => this.notif.errorHttp(err, 'No se pudo quitar la dirección'),
    });
  }

  nombreOdontologo(id: number | null): string {
    return id == null ? '' : (this.odontologos().find(o => o.id === id)?.nombre ?? '');
  }

  tipoCorto(t: RemitenteTipo | null): string {
    return this.tiposRemitente.find(x => x.valor === t)?.corta ?? '';
  }

  /** Un escaneo en un formato que el visor sabe mostrar se puede mirar acá mismo, antes de crear el pedido. */
  puedeVer3d(a: AdjuntoSolicitud): boolean {
    const nombre = a.fileName.toLowerCase();
    return a.tipo === 'ESCANEO' && VISUALIZABLES_3D.some(ext => nombre.endsWith(ext));
  }

  ver3d(s: Solicitud, a: AdjuntoSolicitud): void {
    if (this.cargandoAdjunto() != null) return;
    this.cargandoAdjunto.set(a.id);
    this.svc.descargarAdjunto(s.id, a.id).subscribe({
      next: blob => {
        this.cargandoAdjunto.set(null);
        this.liberarVisor();
        this.urlVisor = URL.createObjectURL(blob);
        this.visor.set({ url: this.urlVisor, nombre: a.fileName });
      },
      error: err => { this.cargandoAdjunto.set(null); this.notif.errorHttp(err, `No se pudo abrir ${a.fileName}`); },
    });
  }

  cerrarVisor(): void {
    this.visor.set(null);
    this.liberarVisor();
  }

  private liberarVisor(): void {
    if (this.urlVisor) URL.revokeObjectURL(this.urlVisor);
    this.urlVisor = null;
  }

  ngOnDestroy(): void {
    this.liberarVisor();
  }

  /** «1 escaneo 3D y 2 documentos»: lo que trajo el mail, para decir qué se guarda con el pedido. */
  resumenAdjuntos(s: Solicitud): string {
    const escaneos = s.adjuntos.filter(a => a.tipo === 'ESCANEO').length;
    const documentos = s.adjuntos.length - escaneos;
    const partes = [
      escaneos > 0 ? `${escaneos} ${escaneos === 1 ? 'escaneo 3D' : 'escaneos 3D'}` : null,
      documentos > 0 ? `${documentos} ${documentos === 1 ? 'documento' : 'documentos'}` : null,
    ].filter((p): p is string => p != null);
    return partes.join(' y ');
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
    return describirTamanio(bytes);
  }

  detalleMalla(a: AdjuntoSolicitud): string {
    return describirMalla(a);
  }

  resumen(s: Solicitud): string {
    const c = s.sugerencia;
    return [c.trabajo, c.paciente ? `paciente ${c.paciente}` : null].filter(Boolean).join(' · ') || 'Sin datos reconocidos';
  }

  /** Quién dice ser el remitente, para mostrarlo en la lista y en el detalle. */
  quienEs(s: Solicitud): string {
    return s.remitenteEtiqueta ?? s.odontologoNombre ?? s.remitenteNombre ?? s.remitenteEmail ?? '';
  }

  textoVacio(): string {
    switch (this.estado()) {
      case 'NUEVA': return 'No hay pedidos por revisar.';
      case 'SIN_AUTORIZAR': return 'No hay mails de remitentes nuevos. Los de alguien que no conocemos aparecen acá en vez de mezclarse con los pedidos.';
      default: return 'No hay mails en este estado.';
    }
  }
}
