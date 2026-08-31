import { Component, OnInit, inject, DestroyRef } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  OdontologosService, OdontologoResponse, OdontologoRequest
} from '../../../services/odontologos.service';
import {
  FinanzasService, ComprobanteResponse, PagoCuentaCorrienteResponse
} from '../../../services/finanzas.service';
import { NotificationService } from '../../../services/notification.service';
import { PagoCuentaCorrienteModalComponent } from './pago-cuenta-corriente-modal/pago-cuenta-corriente-modal.component';
import { AuthService } from '../../../services/auth';
import { iniciarPolling } from '../../../shared/poll.util';

@Component({
  selector: 'app-odontologos',
  standalone: true,
  imports: [FormsModule, RouterLink, PagoCuentaCorrienteModalComponent],
  templateUrl: './odontologos.html',
  styleUrls: ['./odontologos.css'],
})
export class OdontologosComponent implements OnInit {

  odontologos: OdontologoResponse[] = [];
  filtrados: OdontologoResponse[] = [];
  loading = false;
  error = '';

  busqueda = '';
  tipoMatch: 'NOMBRE' | 'DNI' | 'CUIT' | 'MATRICULA' = 'NOMBRE';

  // Filtro de actividad (calculada por último pedido)
  filtroActividad: 'TODOS' | 'ACTIVOS' | 'INACTIVOS' = 'TODOS';

  // Modal
  showModal = false;
  editMode = false;
  saving = false;
  odontologoEditandoId: number | null = null;

  form: OdontologoRequest = this.formVacio();

  // Detalle
  detalleAbierto: OdontologoResponse | null = null;

  // Cuenta corriente del detalle
  ccComprobantes: ComprobanteResponse[] = [];
  ccHistorial: PagoCuentaCorrienteResponse[] = [];
  ccSaldo = 0;
  ccCargando = false;

  // Modal registrar pago
  showModalPago = false;

  // Confirm desactivar
  confirmDesactivarId: number | null = null;

  private notif = inject(NotificationService);
  private finanzas = inject(FinanzasService);
  private auth = inject(AuthService);
  private destroyRef = inject(DestroyRef);

  /** Técnicos no ven deuda/cuenta corriente — solo ADMIN y ADMINISTRATIVO manejan plata. */
  get puedeVerFinanzas(): boolean {
    return this.auth.puedeVerFinanzas();
  }

  constructor(private service: OdontologosService) {}

  ngOnInit(): void {
    this.cargar();
    iniciarPolling(() => this.cargar(true), this.destroyRef);
  }

  // ── CARGA / FILTROS ──────────────────────────────────────────

  private cargar(silencioso = false): void {
    if (silencioso && (this.showModal || this.confirmDesactivarId != null)) return;
    if (!silencioso) { this.loading = true; this.error = ''; }
    // incluirInactivos=true: el panel de gestión necesita ver también los
    // desactivados para que la pestaña "Inactivos" (filtra por o.activo)
    // tenga algo que mostrar — listarActivos() del backend nunca los incluye.
    this.service.buscar(undefined, true).subscribe({
      next: data => {
        this.odontologos = data.sort((a, b) => a.nombre.localeCompare(b.nombre));
        this.filtrar();
        if (!silencioso) this.loading = false;
      },
      error: err => {
        if (!silencioso) {
          this.error = 'No se pudieron cargar los odontólogos. ¿ms-pedidos está corriendo?';
          this.loading = false;
        }
        console.error(err);
      },
    });
  }

  /**
   * Filtra en memoria contra `this.odontologos` (ya trae activos + inactivos,
   * ver cargar()) en vez de volver a pegarle al backend: la búsqueda por
   * nombre/DNI/CUIT/matrícula del backend solo resuelve contra activos, y
   * quería usarse también con la pestaña "Inactivos" seleccionada.
   */
  filtrar(): void {
    const q = this.busqueda.trim();
    this.tipoMatch = this.detectarTipo(q);
    const porTexto = this.filtrarPorTexto(q, this.odontologos);
    this.filtrados = this.aplicarFiltroActividad(porTexto);
  }

  private filtrarPorTexto(q: string, lista: OdontologoResponse[]): OdontologoResponse[] {
    if (!q) return lista;
    const lower = q.toLowerCase();
    if (/^[0-9]{7,8}$/.test(q)) return lista.filter(o => o.dni === q);
    if (/^[0-9]{2}-?[0-9]{8}-?[0-9]{1}$/.test(q)) {
      const norm = this.normalizarCuit(q);
      return lista.filter(o => o.cuit === norm);
    }
    if (/^(MN|MP|MAT)[\s-]*[0-9]+$/i.test(q)) return lista.filter(o => o.matricula?.toLowerCase() === lower);
    return lista.filter(o => o.nombre.toLowerCase().includes(lower));
  }

  /** Convierte cualquier formato de CUIT a XX-XXXXXXXX-X (mismo criterio que el backend). */
  private normalizarCuit(cuit: string): string {
    const digitos = cuit.replace(/[^0-9]/g, '');
    if (digitos.length !== 11) return cuit;
    return `${digitos.slice(0, 2)}-${digitos.slice(2, 10)}-${digitos.slice(10)}`;
  }

  /**
   * Aplica el filtro Activos/Inactivos según `activo` (el que cambia el botón
   * "Desactivar") — antes usaba `inactivoPorTiempo` ("sin pedidos hace N
   * meses"), que es un indicador de negocio totalmente distinto y hacía que
   * desactivar a alguien no lo sacara de "Activos" si tenía pedidos recientes.
   */
  private aplicarFiltroActividad(lista: OdontologoResponse[]): OdontologoResponse[] {
    if (this.filtroActividad === 'ACTIVOS')   return lista.filter(o => o.activo);
    if (this.filtroActividad === 'INACTIVOS') return lista.filter(o => !o.activo);
    return lista;
  }

  setFiltroActividad(f: 'TODOS' | 'ACTIVOS' | 'INACTIVOS'): void {
    this.filtroActividad = f;
    this.filtrar();
  }

  get countActivos(): number {
    return this.odontologos.filter(o => o.activo).length;
  }
  get countInactivos(): number {
    return this.odontologos.filter(o => !o.activo).length;
  }

  /** Texto "hace X meses/días" del último pedido. */
  ultimoPedidoLabel(o: OdontologoResponse): string {
    if (!o.ultimoPedido) return 'Sin pedidos';
    const dias = Math.floor((Date.now() - new Date(o.ultimoPedido).getTime()) / 86_400_000);
    if (dias < 1)   return 'Pidió hoy';
    if (dias < 30)  return `Hace ${dias}d`;
    const meses = Math.floor(dias / 30);
    return `Hace ${meses} mes${meses !== 1 ? 'es' : ''}`;
  }

  private detectarTipo(q: string): 'NOMBRE' | 'DNI' | 'CUIT' | 'MATRICULA' {
    if (/^[0-9]{7,8}$/.test(q))                          return 'DNI';
    if (/^[0-9]{2}-?[0-9]{8}-?[0-9]{1}$/.test(q))        return 'CUIT';
    if (/^(MN|MP|MAT)[\s-]*[0-9]+$/i.test(q))            return 'MATRICULA';
    return 'NOMBRE';
  }

  // ── MODAL CRUD ───────────────────────────────────────────────

  abrirCrear(): void {
    this.editMode = false;
    this.odontologoEditandoId = null;
    this.form = this.formVacio();
    this.showModal = true;
  }

  abrirEditar(o: OdontologoResponse): void {
    this.editMode = true;
    this.odontologoEditandoId = o.id;
    this.form = {
      nombre: o.nombre,
      dni: o.dni ?? '',
      cuit: o.cuit ?? '',
      telefono: o.telefono ?? '',
      email: o.email ?? '',
      matricula: o.matricula ?? '',
      clinica: o.clinica ?? '',
      direccion: o.direccion ?? '',
    };
    this.detalleAbierto = null;
    this.showModal = true;
  }

  cerrarModal(): void {
    this.showModal = false;
  }

  private formVacio(): OdontologoRequest {
    return {
      nombre: '', dni: '', cuit: '', telefono: '',
      email: '', matricula: '', clinica: '', direccion: '',
    };
  }

  get formValido(): boolean {
    return !!this.form.nombre?.trim();
  }

  /** Deja solo dígitos (DNI). */
  sanitizarDni(v: string): string { return (v || '').replace(/[^0-9]/g, '').slice(0, 8); }
  /** Deja solo dígitos y guiones (CUIT). */
  sanitizarCuit(v: string): string { return (v || '').replace(/[^0-9-]/g, '').slice(0, 13); }
  /** Deja solo números y símbolos de teléfono. */
  sanitizarTelefono(v: string): string { return (v || '').replace(/[^0-9+()\-\s]/g, '').slice(0, 30); }

  guardar(): void {
    if (!this.formValido) return;

    // Validaciones de formato (además del backend).
    const dni = this.form.dni?.trim();
    if (dni && !/^[0-9]{7,8}$/.test(dni)) {
      this.notif.alerta('El DNI debe tener 7 u 8 dígitos.', 'DNI inválido'); return;
    }
    const cuit = this.form.cuit?.trim();
    if (cuit && !/^[0-9]{2}-?[0-9]{8}-?[0-9]{1}$/.test(cuit)) {
      this.notif.alerta('El CUIT debe tener 11 dígitos (ej: 20-28456789-3).', 'CUIT inválido'); return;
    }
    const tel = this.form.telefono?.trim();
    if (tel && !/^[0-9+()\-\s]{6,30}$/.test(tel)) {
      this.notif.alerta('El teléfono solo puede tener números y los símbolos + - ( ).', 'Teléfono inválido'); return;
    }

    this.saving = true;

    const request: OdontologoRequest = {
      nombre: this.form.nombre.trim(),
      dni: this.form.dni?.trim() || null,
      cuit: this.form.cuit?.trim() || null,
      telefono: this.form.telefono?.trim() || null,
      email: this.form.email?.trim() || null,
      matricula: this.form.matricula?.trim() || null,
      clinica: this.form.clinica?.trim() || null,
      direccion: this.form.direccion?.trim() || null,
    };

    const op$ = this.editMode && this.odontologoEditandoId
      ? this.service.actualizar(this.odontologoEditandoId, request)
      : this.service.crear(request);

    op$.subscribe({
      next: res => {
        if (this.editMode) {
          const idx = this.odontologos.findIndex(o => o.id === res.id);
          if (idx !== -1) this.odontologos[idx] = res;
          this.notif.exito(`${res.nombre} actualizado correctamente`);
        } else {
          this.odontologos.unshift(res);
          this.notif.exito(`${res.nombre} agregado a la cartera`);
        }
        this.odontologos.sort((a, b) => a.nombre.localeCompare(b.nombre));
        this.filtrar();
        this.saving = false;
        this.cerrarModal();
      },
      error: err => {
        this.saving = false;
        this.notif.errorHttp(err, 'No se pudo guardar el odontólogo');
        console.error(err);
      },
    });
  }

  // ── DETALLE ──────────────────────────────────────────────────

  abrirDetalle(o: OdontologoResponse): void {
    this.detalleAbierto = o;
    if (this.puedeVerFinanzas) this.cargarCuentaCorriente(o.id);
  }

  cerrarDetalle(): void {
    this.detalleAbierto = null;
    this.ccComprobantes = [];
    this.ccHistorial = [];
    this.ccSaldo = 0;
  }

  // ── CUENTA CORRIENTE ─────────────────────────────────────────

  private cargarCuentaCorriente(odontologoId: number): void {
    this.ccCargando = true;
    this.finanzas.comprobantesPorOdontologo(odontologoId).subscribe({
      next: comps => {
        this.ccComprobantes = comps;
        this.ccSaldo = comps.reduce((acc, c) => acc + (c.saldoPendiente ?? 0), 0);
        this.ccCargando = false;
      },
      error: () => { this.ccCargando = false; },
    });
    this.finanzas.historialPagosOdontologo(odontologoId).subscribe({
      next: pagos => { this.ccHistorial = pagos; },
      error: () => {},
    });
  }

  abrirModalPago(): void {
    this.showModalPago = true;
  }

  cerrarModalPago(): void {
    this.showModalPago = false;
  }

  /** El modal ya registró el pago en el backend; acá solo refrescamos la vista. */
  onPagoRegistrado(): void {
    this.showModalPago = false;
    if (this.detalleAbierto) this.cargarCuentaCorriente(this.detalleAbierto.id);
  }

  formatPrecio(n: number | null | undefined): string {
    if (n == null) return '—';
    return new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 0 }).format(n);
  }

  // ── DESACTIVAR ───────────────────────────────────────────────

  pedirConfirmDesactivar(id: number): void {
    // Cierra el modal de edición si estaba abierto, para no superponer modales
    this.showModal = false;
    this.confirmDesactivarId = id;
  }
  abortarDesactivar(): void {
    this.confirmDesactivarId = null;
  }
  confirmarDesactivar(id: number): void {
    const odontologo = this.odontologos.find(o => o.id === id);
    const nombre = odontologo?.nombre ?? 'Odontólogo';
    this.service.desactivar(id).subscribe({
      next: () => {
        // No se saca de this.odontologos: sigue existiendo, solo pasa a
        // aparecer bajo la pestaña "Inactivos" en vez de desaparecer del todo.
        if (odontologo) odontologo.activo = false;
        this.confirmDesactivarId = null;
        this.filtrar();
        if (this.detalleAbierto?.id === id) this.detalleAbierto = null;
        this.notif.alerta(`${nombre} desactivado`);
      },
      error: err => {
        this.confirmDesactivarId = null;
        this.notif.errorHttp(err, 'No se pudo desactivar el odontólogo');
        console.error(err);
      },
    });
  }

  // ── STATS ────────────────────────────────────────────────────

  get totalConCuit(): number {
    return this.odontologos.filter(o => !!o.cuit).length;
  }
  get totalConClinica(): number {
    return this.odontologos.filter(o => !!o.clinica).length;
  }

  // ── HELPERS DE VISTA ─────────────────────────────────────────

  iniciales(nombre: string): string {
    return nombre.replace(/^(Dr\.|Dra\.)\s*/i, '')
      .split(' ')
      .filter(Boolean)
      .slice(0, 2)
      .map(p => p[0]?.toUpperCase() ?? '')
      .join('');
  }

  get modalTitle(): string {
    return this.editMode ? 'Editar odontólogo' : 'Nuevo odontólogo';
  }
}
