import { Component, OnInit, HostListener, inject, DestroyRef } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpEventType } from '@angular/common/http';
import {
  PedidosService, PedidoResponse, PedidoRequest, EstadoPedido, Prioridad
} from '../../../services/pedidos.service';
import { OdontologosService, OdontologoResponse } from '../../../services/odontologos.service';
import { CatalogoService, TipoTrabajoResponse } from '../../../services/catalogo.service';
import { NotificationService } from '../../../services/notification.service';
import { EscaneosService } from '../../../services/escaneos.service';
import { fechaLocal, comoLocalDate } from '../../../services/date-utils';
import { PedidoDetalleModalComponent } from './pedido-detalle-modal/pedido-detalle-modal.component';
import { iniciarPolling } from '../../../shared/poll.util';
import { TableSort } from '../../../shared/table-sort';

type FiltroEstado = EstadoPedido | 'TODOS';

@Component({
  selector: 'app-pedidos',
  standalone: true,
  imports: [FormsModule, PedidoDetalleModalComponent],
  templateUrl: './pedidos.html',
  styleUrls: ['./pedidos.css'],
})
export class PedidosComponent implements OnInit {

  // ── Estado de la lista ───────────────────────────────────────
  pedidos: PedidoResponse[] = [];
  filtrados: PedidoResponse[] = [];
  loading = false;
  error = '';

  readonly sort = new TableSort<PedidoResponse>();
  /** Lista filtrada Y ordenada para la tabla. */
  get filtradosVista(): PedidoResponse[] {
    return this.sort.aplicar(this.filtrados);
  }

  // ── Filtros ──────────────────────────────────────────────────
  busqueda = '';
  estadoActivo: FiltroEstado = 'TODOS';

  readonly estados: { valor: FiltroEstado; label: string }[] = [
    { valor: 'TODOS',      label: 'Todos'        },
    { valor: 'RECIBIDO',   label: 'Recibidos'    },
    { valor: 'EN_PROCESO', label: 'En proceso'   },
    { valor: 'CONTROL',    label: 'Control'      },
    { valor: 'LISTO',      label: 'Listos'       },
    { valor: 'ENTREGADO',  label: 'Entregados'   },
    { valor: 'CANCELADO',  label: 'Cancelados'   },
  ];

  // ── Modal Nuevo / Editar ─────────────────────────────────────
  showModal = false;
  editMode = false;
  saving = false;
  pedidoEditandoId: number | null = null;

  form: {
    odontologoId: number | null;
    odontologoNombre: string;
    paciente: string;
    catalogoTrabajoId: number | null;
    trabajo: string;
    fechaEntrega: string;
    prioridad: Prioridad;
    precioAcordado: number | null;
    observaciones: string;
    // ── Datos opcionales para crear odontólogo nuevo inline ──
    nuevoOdontologo: {
      dni: string;
      cuit: string;
      telefono: string;
      email: string;
      matricula: string;
      clinica: string;
      direccion: string;
    };
  } = this.formVacio();

  /** Controla si el panel "Datos del nuevo odontólogo" está expandido. */
  panelNuevoOdontologoAbierto = false;

  // Autocomplete odontólogo
  odontologosSugeridos: OdontologoResponse[] = [];
  mostrandoSugerencias = false;
  buscandoOdontologo = false;
  tipoMatchOdontologo: 'NOMBRE' | 'DNI' | 'CUIT' | 'MATRICULA' = 'NOMBRE';

  // Autocomplete de trabajo (sobre el catálogo)
  catalogo: TipoTrabajoResponse[] = [];
  trabajosSugeridos: TipoTrabajoResponse[] = [];
  mostrandoSugerenciasTrabajo = false;

  // Validación inline
  errorPrecio = '';

  // ── Detalle (modal compartido, autocontenido — solo necesita el id) ──
  detalleAbiertoId: number | null = null;

  // ── Confirmar cancelar ───────────────────────────────────────
  cancelConfirmId: number | null = null;

  // ── Escaneos 3D a subir junto con el pedido nuevo ─────────────
  readonly acceptEscaneos = '.stl,.obj,.ply,.3ds,.step,.stp,.iges,.igs';
  archivosEscaneo: File[] = [];
  subiendoEscaneos = false;

  private notif = inject(NotificationService);

  constructor(
    private pedidosService: PedidosService,
    private odontologosService: OdontologosService,
    private catalogoService: CatalogoService,
    private escaneosService: EscaneosService,
  ) {}

  private destroyRef = inject(DestroyRef);

  ngOnInit(): void {
    this.cargar();
    this.catalogoService.listar().subscribe({
      next: cat => this.catalogo = cat.filter(t => t.activo),
      error: err => console.error('No se pudo cargar el catálogo:', err),
    });
    iniciarPolling(() => this.cargar(true), this.destroyRef);
  }

  /** Avisa antes de cerrar/refrescar si hay escaneos subiéndose: si se navega, se pierden. */
  @HostListener('window:beforeunload', ['$event'])
  avisarSiHaySubidaEnCurso(event: BeforeUnloadEvent): void {
    if (this.subiendoEscaneos) event.preventDefault();
  }

  // ─────────────────────────────────────────────────────────────
  // CARGA Y FILTROS
  // ─────────────────────────────────────────────────────────────

  private cargar(silencioso = false): void {
    if (!silencioso) { this.loading = true; this.error = ''; }
    this.pedidosService.listarTodos().subscribe({
      next: data => {
        this.pedidos = data.sort((a, b) =>
          new Date(b.fechaCreacion).getTime() - new Date(a.fechaCreacion).getTime());
        this.filtrar();
        if (!silencioso) this.loading = false;
      },
      error: err => {
        if (!silencioso) {
          this.error = 'No se pudieron cargar los pedidos. ¿ms-pedidos está corriendo?';
          this.loading = false;
        }
        console.error(err);
      },
    });
  }

  filtrar(): void {
    const q = this.busqueda.trim().toLowerCase();
    this.filtrados = this.pedidos.filter(p => {
      const matchEstado = this.estadoActivo === 'TODOS' || p.estado === this.estadoActivo;
      const matchTexto = !q || [
        p.nroPedido, p.paciente, p.odontologoNombre, p.trabajo,
      ].some(s => s?.toLowerCase().includes(q));
      return matchEstado && matchTexto;
    });
  }

  setEstadoActivo(e: FiltroEstado): void {
    this.estadoActivo = e;
    this.filtrar();
  }

  countByEstado(e: FiltroEstado): number {
    return e === 'TODOS' ? this.pedidos.length : this.pedidos.filter(p => p.estado === e).length;
  }

  // ─────────────────────────────────────────────────────────────
  // MODAL NUEVO / EDITAR
  // ─────────────────────────────────────────────────────────────

  abrirCrear(): void {
    this.editMode = false;
    this.pedidoEditandoId = null;
    this.form = this.formVacio();
    this.archivosEscaneo = [];
    this.showModal = true;
  }

  abrirEditar(p: PedidoResponse): void {
    this.editMode = true;
    this.pedidoEditandoId = p.id;
    this.form = {
      odontologoId: p.odontologoId,
      odontologoNombre: p.odontologoNombre,
      paciente: p.paciente,
      catalogoTrabajoId: p.catalogoTrabajoId,
      trabajo: p.trabajo,
      fechaEntrega: p.fechaEntrega,
      prioridad: p.prioridad,
      precioAcordado: p.precioAcordado,
      observaciones: p.observaciones ?? '',
      nuevoOdontologo: {
        dni: '', cuit: '', telefono: '', email: '',
        matricula: '', clinica: '', direccion: '',
      },
    };
    this.detalleAbiertoId = null;
    this.showModal = true;
  }

  cerrarModal(): void {
    this.showModal = false;
    this.odontologosSugeridos = [];
    this.mostrandoSugerencias = false;
    this.trabajosSugeridos = [];
    this.mostrandoSugerenciasTrabajo = false;
    this.errorPrecio = '';
    this.archivosEscaneo = [];
  }

  // ── Escaneos 3D a subir junto con el pedido nuevo ─────────────

  onArchivosEscaneoInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files?.length) this.archivosEscaneo.push(...Array.from(input.files));
    input.value = '';
  }

  quitarArchivoEscaneo(idx: number): void {
    this.archivosEscaneo.splice(idx, 1);
  }

  private formVacio(): typeof this.form {
    const hoy = new Date();
    hoy.setDate(hoy.getDate() + 7);
    return {
      odontologoId: null,
      odontologoNombre: '',
      paciente: '',
      catalogoTrabajoId: null,
      trabajo: '',
      fechaEntrega: comoLocalDate(hoy),
      prioridad: 'NORMAL',
      precioAcordado: null,
      observaciones: '',
      nuevoOdontologo: {
        dni: '', cuit: '', telefono: '', email: '',
        matricula: '', clinica: '', direccion: '',
      },
    };
  }

  /** True cuando el nombre tipeado es nuevo (no matchea ninguno existente). */
  get esOdontologoNuevo(): boolean {
    return !this.form.odontologoId &&
           this.form.odontologoNombre.trim().length >= 2 &&
           this.tipoMatchOdontologo === 'NOMBRE';
  }

  /** True cuando el usuario completó al menos un dato extra del odontólogo nuevo. */
  get tieneDatosNuevoOdontologo(): boolean {
    const n = this.form.nuevoOdontologo;
    return !!(n.dni || n.cuit || n.telefono || n.email || n.matricula || n.clinica || n.direccion);
  }

  togglePanelNuevoOdontologo(panelEl?: HTMLElement): void {
    this.panelNuevoOdontologoAbierto = !this.panelNuevoOdontologoAbierto;
    // Al abrir, scrolleamos el panel a la vista para que el usuario lo vea entero
    if (this.panelNuevoOdontologoAbierto && panelEl) {
      setTimeout(() => panelEl.scrollIntoView({ behavior: 'smooth', block: 'start' }), 80);
    }
  }

  get formValido(): boolean {
    const precioOk = this.form.precioAcordado == null || this.form.precioAcordado >= 0;
    return !!(
      this.form.odontologoNombre.trim() &&
      this.form.paciente.trim() &&
      this.form.trabajo.trim() &&
      this.form.fechaEntrega &&
      precioOk
    );
  }

  // ── Autocomplete odontólogo (con detección de tipo) ──────────

  onOdontologoInput(): void {
    // Si el usuario escribe, perdemos el id (puede ser nuevo)
    this.form.odontologoId = null;

    const q = this.form.odontologoNombre.trim();
    this.tipoMatchOdontologo = this.detectarTipoBusqueda(q);

    if (q.length < 2) {
      this.odontologosSugeridos = [];
      this.mostrandoSugerencias = false;
      return;
    }
    this.buscandoOdontologo = true;
    this.odontologosService.buscar(q).subscribe({
      next: data => {
        this.odontologosSugeridos = data.slice(0, 6);
        this.mostrandoSugerencias = this.odontologosSugeridos.length > 0;
        this.buscandoOdontologo = false;

        // Match exacto único por documento → autoseleccionar
        if (this.tipoMatchOdontologo !== 'NOMBRE' && data.length === 1) {
          this.seleccionarOdontologo(data[0]);
        }
      },
      error: err => {
        console.error('Error buscando odontólogos:', err);
        this.buscandoOdontologo = false;
      },
    });
  }

  /** Detecta si el input parece DNI, CUIT, matrícula o nombre. */
  private detectarTipoBusqueda(q: string): 'NOMBRE' | 'DNI' | 'CUIT' | 'MATRICULA' {
    if (/^[0-9]{7,8}$/.test(q))                          return 'DNI';
    if (/^[0-9]{2}-?[0-9]{8}-?[0-9]{1}$/.test(q))        return 'CUIT';
    if (/^(MN|MP|MAT)[\s-]*[0-9]+$/i.test(q))            return 'MATRICULA';
    return 'NOMBRE';
  }

  seleccionarOdontologo(o: OdontologoResponse): void {
    this.form.odontologoId = o.id;
    this.form.odontologoNombre = o.nombre;
    this.odontologosSugeridos = [];
    this.mostrandoSugerencias = false;
    this.tipoMatchOdontologo = 'NOMBRE';
  }

  ocultarSugerenciasConDelay(): void {
    setTimeout(() => (this.mostrandoSugerencias = false), 200);
  }

  // ── Autocomplete del trabajo (busca en el catálogo) ──────────

  onTrabajoInput(): void {
    // Si el usuario edita el texto, perdemos el id del catálogo
    this.form.catalogoTrabajoId = null;
    const q = this.form.trabajo.trim().toLowerCase();
    if (q.length < 1) {
      this.trabajosSugeridos = [];
      this.mostrandoSugerenciasTrabajo = false;
      return;
    }
    this.trabajosSugeridos = this.catalogo
      .filter(c => c.nombre.toLowerCase().includes(q))
      .slice(0, 8);
    this.mostrandoSugerenciasTrabajo = this.trabajosSugeridos.length > 0;
  }

  seleccionarTrabajo(t: TipoTrabajoResponse): void {
    this.form.catalogoTrabajoId = t.id;
    this.form.trabajo = t.nombre;
    if (this.form.precioAcordado == null || this.form.precioAcordado === 0) {
      this.form.precioAcordado = t.precio;
    }
    this.trabajosSugeridos = [];
    this.mostrandoSugerenciasTrabajo = false;
  }

  ocultarSugerenciasTrabajoConDelay(): void {
    setTimeout(() => (this.mostrandoSugerenciasTrabajo = false), 200);
  }

  // ── Validación de precio ─────────────────────────────────────

  onPrecioChange(): void {
    if (this.form.precioAcordado != null && this.form.precioAcordado < 0) {
      this.errorPrecio = 'El precio no puede ser negativo';
    } else {
      this.errorPrecio = '';
    }
  }

  // ── Saneadores de input (odontólogo nuevo inline) ────────────
  /** Deja solo dígitos (DNI). */
  sanitizarDni(v: string): string { return (v || '').replace(/[^0-9]/g, '').slice(0, 8); }
  /** Deja solo dígitos y guiones (CUIT). */
  sanitizarCuit(v: string): string { return (v || '').replace(/[^0-9-]/g, '').slice(0, 13); }
  /** Deja solo números y símbolos de teléfono. */
  sanitizarTelefono(v: string): string { return (v || '').replace(/[^0-9+()\-\s]/g, '').slice(0, 30); }

  // ── Submit ───────────────────────────────────────────────────

  guardar(): void {
    if (!this.formValido) return;
    this.saving = true;

    // Si es un odontólogo nuevo Y el usuario completó datos extras → crearlo primero
    if (this.esOdontologoNuevo && this.tieneDatosNuevoOdontologo) {
      const n = this.form.nuevoOdontologo;
      this.odontologosService.crear({
        nombre: this.form.odontologoNombre.trim(),
        dni: n.dni.trim() || null,
        cuit: n.cuit.trim() || null,
        telefono: n.telefono.trim() || null,
        email: n.email.trim() || null,
        matricula: n.matricula.trim() || null,
        clinica: n.clinica.trim() || null,
        direccion: n.direccion.trim() || null,
      }).subscribe({
        next: odon => {
          this.notif.info(`Odontólogo nuevo creado: ${odon.nombre}`);
          this.form.odontologoId = odon.id;
          this.guardarPedido();
        },
        error: err => {
          this.saving = false;
          this.notif.errorHttp(err, 'No se pudo crear el odontólogo nuevo');
        },
      });
      return;
    }

    // Caso normal: o es existente, o es nuevo solo con nombre (find-or-create del backend)
    this.guardarPedido();
  }

  /** Llama al endpoint de pedidos con el form actual. */
  private guardarPedido(): void {
    const request: PedidoRequest = {
      odontologoId: this.form.odontologoId,
      odontologoNombre: this.form.odontologoNombre.trim(),
      paciente: this.form.paciente.trim(),
      catalogoTrabajoId: this.form.catalogoTrabajoId,
      trabajo: this.form.trabajo.trim(),
      fechaEntrega: this.form.fechaEntrega,
      prioridad: this.form.prioridad,
      precioAcordado: this.form.precioAcordado,
      observaciones: this.form.observaciones?.trim() || null,
    };

    const op$ = this.editMode && this.pedidoEditandoId
      ? this.pedidosService.actualizar(this.pedidoEditandoId, request)
      : this.pedidosService.crear(request);

    op$.subscribe({
      next: res => {
        if (this.editMode) {
          const idx = this.pedidos.findIndex(p => p.id === res.id);
          if (idx !== -1) this.pedidos[idx] = res;
          this.notif.exito(`Pedido ${res.nroPedido} actualizado correctamente`);
        } else {
          this.pedidos.unshift(res);
          this.notif.exito(`Pedido ${res.nroPedido} creado para ${res.paciente}`);
        }
        this.filtrar();
        this.saving = false;
        const archivosPendientes = this.archivosEscaneo;
        this.cerrarModal();
        if (!this.editMode && archivosPendientes.length > 0) {
          this.subirEscaneosPedidoNuevo(res.id, res.nroPedido, archivosPendientes);
        }
      },
      error: err => {
        this.saving = false;
        this.notif.errorHttp(err, 'No se pudo guardar el pedido');
      },
    });
  }

  /** Sube en segundo plano los escaneos que se adjuntaron al crear el pedido. */
  private subirEscaneosPedidoNuevo(pedidoId: number, nroPedido: string, archivos: File[]): void {
    this.subiendoEscaneos = true;
    // Aviso al arrancar, no solo al terminar: el modal ya se cerró para este
    // punto, así que sin esto el usuario no tiene ninguna señal de que algo
    // está pasando hasta que termina de subir (o falla) varios segundos después.
    this.notif.info(`Subiendo ${archivos.length} escaneo(s)…`, `Pedido ${nroPedido}`);
    let subidos = 0;
    let fallidos = 0;

    const subirSiguiente = (i: number): void => {
      if (i >= archivos.length) {
        this.subiendoEscaneos = false;
        if (fallidos === 0) {
          this.notif.exito(`${subidos} escaneo(s) subido(s) al pedido ${nroPedido}`);
        } else {
          this.notif.alerta(`${subidos} escaneo(s) subido(s), ${fallidos} fallaron`, `Pedido ${nroPedido}`);
        }
        return;
      }
      this.escaneosService.subir(pedidoId, archivos[i]).subscribe({
        next: event => {
          if (event.type === HttpEventType.Response) { subidos++; subirSiguiente(i + 1); }
        },
        error: () => { fallidos++; subirSiguiente(i + 1); },
      });
    };
    subirSiguiente(0);
  }

  // ─────────────────────────────────────────────────────────────
  // DETALLE
  // ─────────────────────────────────────────────────────────────

  abrirDetalle(p: PedidoResponse): void {
    this.detalleAbiertoId = p.id;
  }

  cerrarDetalle(): void {
    this.detalleAbiertoId = null;
  }

  // ─────────────────────────────────────────────────────────────
  // ACCIONES DE ESTADO
  // ─────────────────────────────────────────────────────────────

  pedirConfirmCancelar(id: number): void {
    this.cancelConfirmId = id;
  }
  abortarCancel(): void {
    this.cancelConfirmId = null;
  }

  confirmarCancelar(id: number): void {
    this.pedidosService.actualizarEstado(id, 'CANCELADO').subscribe({
      next: res => {
        const idx = this.pedidos.findIndex(p => p.id === id);
        if (idx !== -1) this.pedidos[idx] = res;
        this.cancelConfirmId = null;
        this.filtrar();
        this.notif.alerta(`Pedido ${res.nroPedido} cancelado`);
      },
      error: err => {
        this.notif.errorHttp(err, 'No se pudo cancelar el pedido');
        this.cancelConfirmId = null;
      },
    });
  }

  // ─────────────────────────────────────────────────────────────
  // HELPERS DE VISTA
  // ─────────────────────────────────────────────────────────────

  labelEstado(e: EstadoPedido): string {
    const m: Record<EstadoPedido, string> = {
      RECIBIDO: 'Recibido', EN_PROCESO: 'En proceso',
      CONTROL: 'Control', LISTO: 'Listo',
      ENTREGADO: 'Entregado', CANCELADO: 'Cancelado',
    };
    return m[e] ?? e;
  }

  colorEstado(e: EstadoPedido): string {
    const m: Record<EstadoPedido, string> = {
      RECIBIDO: 'blue', EN_PROCESO: 'amber',
      CONTROL: 'purple', LISTO: 'green',
      ENTREGADO: 'cyan', CANCELADO: 'rose',
    };
    return m[e] ?? 'muted';
  }

  /** Delega al service para que toda la app use la misma lógica. */
  estaAtrasado(p: PedidoResponse): boolean {
    return this.pedidosService.estaAtrasado(p);
  }

  formatFecha(iso: string | null): string {
    if (!iso) return '—';
    return new Date(iso).toLocaleDateString('es-AR', { day: '2-digit', month: '2-digit', year: 'numeric' });
  }

  formatPrecio(n: number | null): string {
    if (n == null) return 'A convenir';
    return new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 0 }).format(n);
  }

  diasRestantes(fechaEntrega: string): { dias: number; texto: string; clase: string } {
    const hoy = new Date(); hoy.setHours(0, 0, 0, 0);
    const fe = fechaLocal(fechaEntrega); fe.setHours(0, 0, 0, 0);
    const dias = Math.round((fe.getTime() - hoy.getTime()) / 86_400_000);
    if (dias < 0)  return { dias, texto: `Atrasado (${Math.abs(dias)}d)`, clase: 'vencido' };
    if (dias === 0) return { dias, texto: 'Entrega hoy',                   clase: 'urgente' };
    if (dias === 1) return { dias, texto: 'Entrega mañana',                clase: 'urgente' };
    if (dias <= 2)  return { dias, texto: `Entrega en ${dias}d`,           clase: 'urgente' };
    if (dias <= 7)  return { dias, texto: `Entrega en ${dias}d`,           clase: 'pronto'  };
    return { dias, texto: `Entrega en ${dias}d`, clase: 'normal' };
  }

  get modalTitle(): string {
    return this.editMode ? 'Editar pedido' : 'Nuevo pedido';
  }
}
