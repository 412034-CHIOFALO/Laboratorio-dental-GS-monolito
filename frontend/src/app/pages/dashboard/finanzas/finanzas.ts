import { Component, OnInit, inject, DestroyRef } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink, ActivatedRoute } from '@angular/router';
import { forkJoin } from 'rxjs';
import { iniciarPolling } from '../../../shared/poll.util';
import { fechaLocal } from '../../../services/date-utils';
import {
  FinanzasService, ResumenCajasResponse, CajaMovimientoResponse, TipoCaja,
  CuentaCorrienteOdontologoResponse, SeveridadDeuda,
  CajaMovimientoRequest, CategoriaMovimiento, TipoMovimientoCaja
} from '../../../services/finanzas.service';
import {
  SueldosService, EmpleadoSueldo, FrecuenciaPago, ConfigSueldoRequest, PagoSueldoRequest,
  PagoSueldoResponse, RegistroBot, DistribucionCascada, CrearEmpleadoRequest
} from '../../../services/sueldos.service';
import { NotificationService } from '../../../services/notification.service';
import { AuthService, UsuarioListado } from '../../../services/auth';
import { ProveedoresService } from '../../../services/proveedores.service';
import { PagoCuentaCorrienteModalComponent } from '../odontologos/pago-cuenta-corriente-modal/pago-cuenta-corriente-modal.component';

export type FiltroMorosos = 'TODOS' | 'MOROSOS' | 'MAS_30' | 'MAS_60';
export type FiltroMovimientos = 'TODOS' | 'INGRESO' | 'EGRESO';
export type PeriodoMov = 'HOY' | 'SEMANA' | 'MES' | 'TODO';
export type SortDir = 'asc' | 'desc';

/** Peso de cada severidad para poder ordenarla por gravedad real (no alfabético). */
const SEVERIDAD_PESO: Record<string, number> = {
  AL_DIA: 0, BAJA: 1, MEDIA: 2, ALTA: 3, CRITICA: 4,
};

/** Apartados (pills) del módulo de Finanzas. */
export type SeccionFinanzas =
  | 'resumen' | 'cajas' | 'cuentas' | 'triangulados' | 'proveedores' | 'sueldos';

interface SortState<K> {
  campo: K;
  dir: SortDir;
}

/**
 * Fila unificada para "Comprobantes recibidos": junta pagos de sueldo (PagoSueldo,
 * tabla de empleados), registros del bot a proveedores/triangulados (RegistroPagoBot)
 * y pagos manuales a proveedores (DeudaProveedor con estado PAGADO) — tres tablas
 * de backend distintas. Antes solo se juntaban las dos primeras, así que un pago a
 * proveedor cargado a mano desde la pantalla de Proveedores nunca aparecía acá.
 */
interface ComprobanteRecibido {
  id: number;
  fuente: 'SUELDO' | 'BOT_REGISTRO' | 'PROVEEDOR_MANUAL';
  fecha: string;
  origen: 'MANUAL' | 'BOT_WHATSAPP';
  tipoReceptor: 'EMPLEADO' | 'PROVEEDOR';
  receptorNombre: string;
  emisor: string | null;
  cargadoPorNombre: string | null;
  monto: number;
  tieneComprobante: boolean;
}

@Component({
  selector: 'app-finanzas',
  standalone: true,
  imports: [FormsModule, RouterLink, PagoCuentaCorrienteModalComponent],
  templateUrl: './finanzas.html',
  styleUrls: ['./finanzas.css'],
})
export class FinanzasComponent implements OnInit {

  // ── Navegación por pills ─────────────────────────────────────────
  seccionActiva: SeccionFinanzas = 'resumen';

  readonly secciones: { id: SeccionFinanzas; label: string }[] = [
    { id: 'resumen',      label: 'Resumen' },
    { id: 'cajas',        label: 'Cajas' },
    { id: 'cuentas',      label: 'Cuentas corrientes' },
    { id: 'triangulados', label: 'Triangulados' },
    { id: 'proveedores',  label: 'Proveedores' },
    { id: 'sueldos',      label: 'Sueldos' },
  ];

  irA(seccion: SeccionFinanzas): void {
    this.seccionActiva = seccion;
    // Scroll al tope al cambiar de apartado
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  /** Va a Cajas → Compensación, filtrado por la referencia del triangulado incompleto. */
  verDescuadre(referencia: string | null): void {
    this.irA('cajas');
    this.busquedaMovimiento = referencia ?? '';
    this.cambiarCaja('COMPENSACION');
  }

  resumen: ResumenCajasResponse | null = null;
  loadingResumen = false;
  errorResumen = '';

  cajaActiva: TipoCaja = 'FISICA';
  movimientos: CajaMovimientoResponse[] = [];
  movimientosFiltrados: CajaMovimientoResponse[] = [];
  loadingMovimientos = false;

  // Filtros y sort de movimientos
  filtroMovimientos: FiltroMovimientos = 'TODOS';
  periodoMovimientos: PeriodoMov = 'TODO';
  busquedaMovimiento = '';
  sortMov: SortState<keyof CajaMovimientoResponse> = { campo: 'fechaMovimiento', dir: 'desc' };

  readonly periodos: { valor: PeriodoMov; label: string }[] = [
    { valor: 'HOY',    label: 'Hoy' },
    { valor: 'SEMANA', label: 'Semana' },
    { valor: 'MES',    label: 'Mes' },
    { valor: 'TODO',   label: 'Todo' },
  ];

  // Sort de cuentas corrientes
  sortCuentas: SortState<keyof CuentaCorrienteOdontologoResponse> = { campo: 'totalDeuda', dir: 'desc' };

  // ── Cuentas corrientes (morosos) ────────────────────────────────
  cuentasCorrientes: CuentaCorrienteOdontologoResponse[] = [];
  cuentasFiltradas: CuentaCorrienteOdontologoResponse[] = [];
  loadingCuentas = false;
  filtroMorosos: FiltroMorosos = 'TODOS';
  busquedaMoroso = '';

  // Modal registrar pago (desde el ranking de morosos, sin salir de Finanzas)
  odontologoPago: CuentaCorrienteOdontologoResponse | null = null;

  private notif = inject(NotificationService);
  private destroyRef = inject(DestroyRef);
  private route = inject(ActivatedRoute);

  readonly filtros: { valor: FiltroMorosos; label: string }[] = [
    { valor: 'TODOS',   label: 'Todos' },
    { valor: 'MOROSOS', label: 'Solo morosos' },
    { valor: 'MAS_30',  label: '+30 días' },
    { valor: 'MAS_60',  label: '+60 días' },
  ];

  // Cajas REALES navegables (donde hay plata del lab). La compensación no es
  // una caja navegable: vive dentro de "Pagos triangulados" (ver pill aparte).
  readonly cajas: { tipo: TipoCaja; label: string; descripcion: string; color: string }[] = [
    { tipo: 'FISICA',   label: 'Caja Física',   descripcion: 'Efectivo en mano',                       color: 'green' },
    { tipo: 'BANCARIA', label: 'Caja Bancaria', descripcion: 'Transferencias y movimientos bancarios', color: 'cyan'  },
  ];

  // ── Modal de carga manual de movimiento ──────────────────────────
  showMovModal = false;
  savingMov = false;
  movForm: CajaMovimientoRequest = this.movFormVacio();

  readonly categoriasMovimiento: { valor: CategoriaMovimiento; label: string; tipoSugerido: TipoMovimientoCaja }[] = [
    { valor: 'COBRO_ODONTOLOGO', label: 'Cobro a odontólogo',  tipoSugerido: 'INGRESO' },
    { valor: 'EFECTIVO_CADETE',  label: 'Efectivo del cadete', tipoSugerido: 'INGRESO' },
    { valor: 'PAGO_PROVEEDOR',   label: 'Pago a proveedor',    tipoSugerido: 'EGRESO'  },
    { valor: 'PAGO_SUELDO',      label: 'Pago de sueldo',      tipoSugerido: 'EGRESO'  },
    { valor: 'GASTO_MENOR',      label: 'Gasto menor',         tipoSugerido: 'EGRESO'  },
    { valor: 'AJUSTE',           label: 'Ajuste',              tipoSugerido: 'INGRESO' },
    { valor: 'OTRO',             label: 'Otro',                tipoSugerido: 'INGRESO' },
  ];

  // ── Sueldos ──────────────────────────────────────────────────────
  empleados: EmpleadoSueldo[] = [];
  loadingEmpleados = false;

  // Modal config de sueldo
  showConfigModal = false;
  empleadoEditando: EmpleadoSueldo | null = null;
  configForm: ConfigSueldoRequest = { frecuencia: 'MENSUAL', montoBase: 0 };
  savingConfig = false;

  // Modal alta de empleado nuevo (usuarios de ms-auth sin dar de alta en sueldos)
  showNuevoEmpleadoModal = false;
  usuariosSinAlta: UsuarioListado[] = [];
  loadingUsuariosSinAlta = false;
  nuevoEmpleadoForm: CrearEmpleadoRequest = this.nuevoEmpleadoFormVacio();
  savingNuevoEmpleado = false;

  // Modal registrar pago
  showPagoModal = false;
  empleadoPagando: EmpleadoSueldo | null = null;
  pagoForm: PagoSueldoRequest = { usuarioId: 0, monto: 0, manejoSobrante: 'DESCONTAR_PROXIMO' };
  savingPago = false;

  // Modal cascada: sugiere como distribuir un cobro entre sueldos pendientes + caja
  showCascadaModal = false;
  cascadaMonto: number | null = null;
  cascadaCaja: TipoCaja = 'FISICA';
  cascadaLineas: { empleadoId: number; empleadoNombre: string; monto: number }[] = [];
  cascadaSugerida = false;
  sugiriendoCascada = false;
  confirmandoCascada = false;

  // Modal histórico de pagos
  showHistorialModal = false;
  empleadoHistorial: EmpleadoSueldo | null = null;
  historial: PagoSueldoResponse[] = [];
  loadingHistorial = false;

  // ── Comprobantes recibidos (sueldos + proveedores/triangulados) ──
  comprobantes: ComprobanteRecibido[] = [];
  comprobantesFiltrados: ComprobanteRecibido[] = [];
  loadingComprobantes = false;
  busquedaComprobante = '';
  filtroOrigen: 'TODOS' | 'BOT_WHATSAPP' | 'MANUAL' = 'TODOS';

  // ── Efectivo pendiente ───────────────────────────────────────────
  pendientesEfectivo: RegistroBot[] = [];
  loadingPendientes = false;
  rechazandoId: number | null = null;
  motivoRechazo = '';

  readonly frecuencias: { valor: FrecuenciaPago; label: string }[] = [
    { valor: 'DIARIO',    label: 'Por día trabajado' },
    { valor: 'SEMANAL',   label: 'Semanal' },
    { valor: 'QUINCENAL', label: 'Quincenal' },
    { valor: 'MENSUAL',   label: 'Mensual' },
  ];

  constructor(
    private finanzasService: FinanzasService,
    private sueldosService: SueldosService,
    private authService: AuthService,
    private proveedoresService: ProveedoresService,
  ) {}

  private movFormVacio(): CajaMovimientoRequest {
    return {
      tipo: 'INGRESO',
      tipoCaja: 'FISICA',
      concepto: '',
      monto: 0,
      categoria: 'COBRO_ODONTOLOGO',
      referencia: '',
      fechaMovimiento: new Date().toISOString().slice(0, 10),
    };
  }

  abrirNuevoMovimiento(): void {
    this.movForm = this.movFormVacio();
    this.movForm.tipoCaja = this.cajaActiva;   // arranca con la caja activa
    this.showMovModal = true;
  }

  cerrarMovModal(): void {
    this.showMovModal = false;
  }

  /** Al cambiar la categoría, sugerimos el tipo (ingreso/egreso) más común. */
  onCategoriaMovChange(): void {
    const cat = this.categoriasMovimiento.find(c => c.valor === this.movForm.categoria);
    if (cat) this.movForm.tipo = cat.tipoSugerido;
  }

  get movFormValido(): boolean {
    return !!this.movForm.concepto?.trim()
        && this.movForm.monto != null
        && this.movForm.monto > 0;
  }

  guardarMovimiento(): void {
    if (!this.movFormValido) {
      this.notif.alerta('Completá el concepto y un monto mayor a cero');
      return;
    }
    this.savingMov = true;
    this.finanzasService.registrarMovimientoCaja(this.movForm).subscribe({
      next: res => {
        this.savingMov = false;
        this.showMovModal = false;
        const signo = res.tipo === 'INGRESO' ? '+' : '−';
        this.notif.exito(`${signo} ${this.formatMoney(res.monto)} registrado en ${this.cajaLabel(res.tipoCaja)}`);
        // Si el movimiento es de la caja que estamos viendo, refrescamos
        if (res.tipoCaja === this.cajaActiva) {
          this.cargarMovimientos();
        } else {
          this.cajaActiva = res.tipoCaja;
          this.cargarMovimientos();
        }
      },
      error: err => {
        this.savingMov = false;
        this.notif.errorHttp(err, 'No se pudo registrar el movimiento');
      },
    });
  }

  // ── Reportes PDF (cierre diario / resumen mensual) ────────────
  descargandoPdf = false;

  descargarCierreDiario(): void {
    this.descargandoPdf = true;
    const hoy = new Date().toISOString().slice(0, 10);
    this.finanzasService.descargarCierreDiarioPdf(hoy).subscribe({
      next: blob => { this.abrirPdf(blob); this.descargandoPdf = false; },
      error: err => { this.descargandoPdf = false; this.notif.errorHttp(err, 'No se pudo generar el cierre diario'); },
    });
  }

  descargarResumenMensual(): void {
    this.descargandoPdf = true;
    const ahora = new Date();
    this.finanzasService.descargarResumenMensualPdf(ahora.getFullYear(), ahora.getMonth() + 1).subscribe({
      next: blob => { this.abrirPdf(blob); this.descargandoPdf = false; },
      error: err => { this.descargandoPdf = false; this.notif.errorHttp(err, 'No se pudo generar el resumen mensual'); },
    });
  }

  /** Abre el PDF generado en una pestaña nueva. */
  private abrirPdf(blob: Blob): void {
    const url = URL.createObjectURL(blob);
    window.open(url, '_blank');
    setTimeout(() => URL.revokeObjectURL(url), 15000);
  }

  private cajaLabel(t: TipoCaja): string {
    return this.cajas.find(c => c.tipo === t)?.label ?? t;
  }

  ngOnInit(): void {
    // 'proveedores' está en `secciones` solo para el ícono del pill, que
    // redirige a /dashboard/proveedores en vez de renderizar contenido acá
    // (el @switch de abajo no tiene @case para esa sección) — si un link
    // viejo llega con ?seccion=proveedores, cae a 'resumen' en vez de dejar
    // la pantalla en blanco para siempre.
    const seccion = this.route.snapshot.queryParamMap.get('seccion') as SeccionFinanzas | null;
    if (seccion && seccion !== 'proveedores' && this.secciones.some(s => s.id === seccion)) {
      this.seccionActiva = seccion;
    }
    this.cargarResumen();
    this.cargarMovimientos();
    this.cargarCuentasCorrientes();
    this.cargarEmpleados();
    this.cargarComprobantes();
    this.cargarPendientesEfectivo();
    iniciarPolling(() => this.refrescarSilencioso(), this.destroyRef);
  }

  /** Refresco de fondo para multi-pestaña. No toca pantallas con un modal o edición en curso. */
  private refrescarSilencioso(): void {
    this.cargarResumen(true);
    this.cargarMovimientos(true);
    this.cargarCuentasCorrientes(true);
    this.cargarComprobantes(true);
    if (!this.hayModalSueldosAbierto) this.cargarEmpleados(true);
    if (this.rechazandoId == null) this.cargarPendientesEfectivo(true);
  }

  private get hayModalSueldosAbierto(): boolean {
    return this.showConfigModal || this.showPagoModal || this.showCascadaModal
        || this.showHistorialModal || this.showNuevoEmpleadoModal;
  }

  // ═════════════════════════ COMPROBANTES (TRIANGULADOS) ═════════════════════════

  /**
   * Junta dos fuentes de backend: PagoSueldo (empleados, vía historialPagosGlobal)
   * y RegistroPagoBot (proveedores/triangulados registrados por el bot, vía
   * registrosBot). Antes esta pestaña solo leía la primera, así que un comprobante
   * a un proveedor quedaba invisible acá aunque el bot lo hubiera cargado bien.
   */
  cargarComprobantes(silencioso = false): void {
    if (!silencioso) this.loadingComprobantes = true;
    forkJoin({
      sueldos: this.sueldosService.historialPagosGlobal(),
      registrosBot: this.sueldosService.registrosBot(),
      deudasPagadas: this.proveedoresService.deudasPagadas(),
    }).subscribe({
      next: ({ sueldos, registrosBot, deudasPagadas }) => {
        const deSueldos: ComprobanteRecibido[] = sueldos.map(p => ({
          id: p.id,
          fuente: 'SUELDO',
          fecha: p.fecha,
          origen: p.origen,
          tipoReceptor: 'EMPLEADO',
          receptorNombre: p.empleadoNombre,
          emisor: p.emisor,
          cargadoPorNombre: p.cargadoPorNombre,
          monto: p.monto,
          tieneComprobante: !!p.comprobanteUrl,
        }));
        const deProveedores: ComprobanteRecibido[] = registrosBot
          .filter(r => r.estado === 'REGISTRADO' && r.tipoReceptor === 'PROVEEDOR')
          .map(r => ({
            id: r.id,
            fuente: 'BOT_REGISTRO',
            fecha: r.fechaHora,
            // El bot siempre trae grupoOrigen (nombre del grupo de WhatsApp); los
            // triangulados a proveedor cargados a mano desde Cuentas Corrientes
            // usan este mismo RegistroPagoBot para no quedar invisibles acá, pero
            // nunca tienen grupoOrigen — así los distinguimos sin tocar el modelo.
            origen: r.grupoOrigen ? 'BOT_WHATSAPP' : 'MANUAL',
            tipoReceptor: 'PROVEEDOR',
            receptorNombre: r.receptorResuelto ?? r.receptorNombre ?? '—',
            emisor: r.emisor,
            cargadoPorNombre: r.cargadoPorNombre,
            monto: r.monto ?? 0,
            tieneComprobante: r.tieneComprobante,
          }));
        // Pagos a proveedores cargados a mano desde "Marcar pagada" (Proveedores) —
        // no pasan por RegistroPagoBot, así que sin esto quedaban invisibles acá.
        const deProveedoresManual: ComprobanteRecibido[] = deudasPagadas.map(d => ({
          id: d.id,
          fuente: 'PROVEEDOR_MANUAL',
          fecha: d.fechaPago ?? '',
          origen: 'MANUAL',
          tipoReceptor: 'PROVEEDOR',
          receptorNombre: d.proveedorNombre,
          emisor: null,
          cargadoPorNombre: null,
          monto: d.montoPagado,
          tieneComprobante: false,
        }));
        this.comprobantes = [...deSueldos, ...deProveedores, ...deProveedoresManual]
          .sort((a, b) => new Date(b.fecha).getTime() - new Date(a.fecha).getTime());
        this.aplicarFiltroComprobantes();
        if (!silencioso) this.loadingComprobantes = false;
      },
      error: err => {
        if (!silencioso) {
          this.loadingComprobantes = false;
          this.notif.errorHttp(err, 'No se pudieron cargar los comprobantes');
        }
      },
    });
  }

  cambiarFiltroOrigen(f: 'TODOS' | 'BOT_WHATSAPP' | 'MANUAL'): void {
    this.filtroOrigen = f;
    this.aplicarFiltroComprobantes();
  }

  aplicarFiltroComprobantes(): void {
    let r = [...this.comprobantes];
    if (this.filtroOrigen !== 'TODOS') r = r.filter(c => c.origen === this.filtroOrigen);
    const q = this.busquedaComprobante.trim().toLowerCase();
    if (q) {
      r = r.filter(c =>
        (c.emisor ?? '').toLowerCase().includes(q) ||
        c.receptorNombre.toLowerCase().includes(q) ||
        (c.cargadoPorNombre ?? '').toLowerCase().includes(q) ||
        String(c.monto).includes(q)
      );
    }
    this.comprobantesFiltrados = r;
  }

  get totalComprobantes(): number { return this.comprobantes.length; }
  get totalPorBot(): number { return this.comprobantes.filter(c => c.origen === 'BOT_WHATSAPP').length; }
  get montoTotalComprobantes(): number { return this.comprobantes.reduce((s, c) => s + c.monto, 0); }

  labelTipoReceptor(t: 'EMPLEADO' | 'PROVEEDOR'): string {
    return t === 'PROVEEDOR' ? 'Proveedor' : 'Sueldo';
  }

  /** Abre el comprobante guardado (PDF/imagen) en una pestaña nueva. */
  verComprobante(c: ComprobanteRecibido): void {
    if (!c.tieneComprobante) { this.notif.alerta('Este pago no tiene comprobante guardado'); return; }
    const descarga$ = c.fuente === 'SUELDO'
      ? this.sueldosService.descargarComprobante(c.id)
      : this.sueldosService.descargarComprobanteRegistro(c.id);
    descarga$.subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        window.open(url, '_blank');
        setTimeout(() => URL.revokeObjectURL(url), 15000);
      },
      error: err => this.notif.errorHttp(err, 'No se pudo abrir el comprobante'),
    });
  }

  // ═════════════════════════ SUELDOS ═════════════════════════

  recalculandoDevengado = false;

  /** Fuerza el cálculo de devengado diario ahora (sin esperar al cron de las 00:05). */
  recalcularDevengado(): void {
    this.recalculandoDevengado = true;
    this.sueldosService.devengarAhora().subscribe({
      next: () => {
        this.recalculandoDevengado = false;
        this.notif.exito('Devengado recalculado');
        this.cargarEmpleados();
      },
      error: err => {
        this.recalculandoDevengado = false;
        this.notif.errorHttp(err, 'No se pudo recalcular el devengado');
      },
    });
  }

  cargarEmpleados(silencioso = false): void {
    if (!silencioso) this.loadingEmpleados = true;
    this.sueldosService.listarEmpleados().subscribe({
      next: data => {
        this.empleados = data;
        if (!silencioso) this.loadingEmpleados = false;
        this.cargarUsuariosSinAlta(silencioso);
      },
      error: err => {
        if (!silencioso) {
          this.loadingEmpleados = false;
          this.notif.errorHttp(err, 'No se pudieron cargar los empleados');
        }
      },
    });
  }

  /** Usuarios de ms-auth que todavía no tienen sueldo configurado (caso excepcional: ver nota en el HTML). */
  cargarUsuariosSinAlta(silencioso = false): void {
    if (!silencioso) this.loadingUsuariosSinAlta = true;
    this.authService.listarUsuarios().subscribe({
      next: usuarios => {
        const yaDadosDeAlta = new Set(this.empleados.map(e => e.usuarioId));
        this.usuariosSinAlta = usuarios.filter(u =>
          !yaDadosDeAlta.has(u.id)
          && u.username !== 'bot-pedidos'        // cuenta de servicio del bot, no un empleado
          && !u.rol.includes('ODONTOLOGO'));      // cliente del labo, no cobra sueldo
        if (!silencioso) this.loadingUsuariosSinAlta = false;
      },
      error: () => { if (!silencioso) this.loadingUsuariosSinAlta = false; },
    });
  }

  get totalDevengado(): number {
    return this.empleados.reduce((sum, e) => sum + e.saldoDevengado, 0);
  }

  get empleadosConDeuda(): number {
    return this.empleados.filter(e => e.saldoDevengado > 0).length;
  }

  labelFrecuencia(f: FrecuenciaPago): string {
    return this.frecuencias.find(x => x.valor === f)?.label ?? f;
  }

  // ── Modal config de sueldo ──
  abrirConfigSueldo(e: EmpleadoSueldo): void {
    this.empleadoEditando = e;
    this.configForm = { frecuencia: e.frecuencia, montoBase: e.montoBase };
    this.showConfigModal = true;
  }
  cerrarConfigModal(): void { this.showConfigModal = false; this.empleadoEditando = null; }

  // ── Modal alta de empleado nuevo (se abre desde la fila del usuario pendiente) ──
  usuarioSeleccionado: UsuarioListado | null = null;

  private nuevoEmpleadoFormVacio(): CrearEmpleadoRequest {
    return { usuarioId: 0, nombre: '', rol: '', telefono: '', frecuencia: 'MENSUAL', montoBase: 0 };
  }

  abrirNuevoEmpleado(u: UsuarioListado): void {
    this.usuarioSeleccionado = u;
    this.nuevoEmpleadoForm = {
      usuarioId: u.id,
      nombre: `${u.nombre} ${u.apellido}`.trim(),
      rol: u.rol.replace('ROLE_', ''),
      telefono: '',
      frecuencia: 'MENSUAL',
      montoBase: 0,
    };
    this.showNuevoEmpleadoModal = true;
  }

  cerrarNuevoEmpleadoModal(): void {
    this.showNuevoEmpleadoModal = false;
    this.usuarioSeleccionado = null;
  }

  get nuevoEmpleadoValido(): boolean {
    return this.nuevoEmpleadoForm.usuarioId > 0
        && !!this.nuevoEmpleadoForm.nombre?.trim()
        && this.nuevoEmpleadoForm.montoBase >= 0;
  }

  crearEmpleado(): void {
    if (!this.nuevoEmpleadoValido) return;
    this.savingNuevoEmpleado = true;
    this.sueldosService.crearEmpleado(this.nuevoEmpleadoForm).subscribe({
      next: creado => {
        this.empleados = [...this.empleados, creado].sort((a, b) => a.nombre.localeCompare(b.nombre));
        this.usuariosSinAlta = this.usuariosSinAlta.filter(u => u.id !== creado.usuarioId);
        this.savingNuevoEmpleado = false;
        this.showNuevoEmpleadoModal = false;
        this.usuarioSeleccionado = null;
        this.notif.exito(`${creado.nombre} dado de alta en sueldos`);
      },
      error: err => {
        this.savingNuevoEmpleado = false;
        this.notif.errorHttp(err, 'No se pudo dar de alta al empleado');
      },
    });
  }

  guardarConfig(): void {
    if (!this.empleadoEditando) return;
    if (this.configForm.montoBase < 0) { this.notif.alerta('El monto no puede ser negativo'); return; }
    this.savingConfig = true;
    this.sueldosService.actualizarConfig(this.empleadoEditando.usuarioId, this.configForm).subscribe({
      next: actualizado => {
        const i = this.empleados.findIndex(x => x.usuarioId === actualizado.usuarioId);
        if (i !== -1) this.empleados[i] = actualizado;
        this.savingConfig = false;
        this.showConfigModal = false;
        this.notif.exito(`Sueldo de ${actualizado.nombre} actualizado`);
      },
      error: err => { this.savingConfig = false; this.notif.errorHttp(err, 'No se pudo guardar'); },
    });
  }

  // ── Modal registrar pago ──
  abrirPagoSueldo(e: EmpleadoSueldo): void {
    this.empleadoPagando = e;
    this.pagoForm = {
      usuarioId: e.usuarioId,
      monto: e.saldoDevengado,   // sugerimos pagar lo que se le debe
      manejoSobrante: 'DESCONTAR_PROXIMO',
      fecha: new Date().toISOString().slice(0, 10),
    };
    this.showPagoModal = true;
  }
  cerrarPagoModal(): void { this.showPagoModal = false; this.empleadoPagando = null; }

  /** True si el monto a pagar supera lo devengado (paga de más). */
  get pagoExcede(): boolean {
    return !!this.empleadoPagando && this.pagoForm.monto > this.empleadoPagando.saldoDevengado;
  }
  get montoExcedente(): number {
    if (!this.empleadoPagando) return 0;
    return Math.max(0, this.pagoForm.monto - this.empleadoPagando.saldoDevengado);
  }

  guardarPago(): void {
    if (!this.empleadoPagando) return;
    if (this.pagoForm.monto <= 0) { this.notif.alerta('El monto debe ser mayor a cero'); return; }
    this.savingPago = true;
    this.sueldosService.registrarPago(this.pagoForm).subscribe({
      next: actualizado => {
        const i = this.empleados.findIndex(x => x.usuarioId === actualizado.usuarioId);
        if (i !== -1) this.empleados[i] = actualizado;
        this.savingPago = false;
        this.showPagoModal = false;
        this.notif.exito(`Pago de ${this.formatMoney(this.pagoForm.monto)} registrado a ${actualizado.nombre}`);
      },
      error: err => { this.savingPago = false; this.notif.errorHttp(err, 'No se pudo registrar el pago'); },
    });
  }

  // ── Modal cascada (distribuir un cobro) ──
  abrirCascadaModal(): void {
    this.cascadaMonto = null;
    this.cascadaCaja = 'FISICA';
    this.cascadaLineas = [];
    this.cascadaSugerida = false;
    this.showCascadaModal = true;
  }
  cerrarCascadaModal(): void { this.showCascadaModal = false; }

  sugerirCascada(): void {
    if (!this.cascadaMonto || this.cascadaMonto <= 0) {
      this.notif.alerta('Ingresá el monto del cobro'); return;
    }
    this.sugiriendoCascada = true;
    this.sueldosService.sugerirCascada(this.cascadaMonto, this.cascadaCaja).subscribe({
      next: (d: DistribucionCascada) => {
        this.cascadaLineas = d.empleados.map(l => ({ ...l }));
        this.cascadaSugerida = true;
        this.sugiriendoCascada = false;
      },
      error: err => { this.sugiriendoCascada = false; this.notif.errorHttp(err, 'No se pudo calcular la distribución'); },
    });
  }

  get cascadaTotalAsignado(): number {
    return this.cascadaLineas.reduce((s, l) => s + (l.monto || 0), 0);
  }
  get cascadaRemanente(): number {
    return Math.max(0, (this.cascadaMonto ?? 0) - this.cascadaTotalAsignado);
  }
  get cascadaExcede(): boolean {
    return this.cascadaTotalAsignado > (this.cascadaMonto ?? 0);
  }

  confirmarCascada(): void {
    if (this.cascadaExcede) {
      this.notif.alerta('La suma asignada no puede superar el monto del cobro'); return;
    }
    this.confirmandoCascada = true;
    const lineas = this.cascadaLineas.filter(l => l.monto > 0);
    const remanente = this.cascadaRemanente;

    const aplicarSiguiente = (i: number): void => {
      if (i >= lineas.length) {
        if (remanente > 0) { aplicarRemanente(); return; }
        finalizar();
        return;
      }
      const l = lineas[i];
      this.sueldosService.registrarPago({
        usuarioId: l.empleadoId, monto: l.monto, manejoSobrante: 'DESCONTAR_PROXIMO',
        fecha: new Date().toISOString().slice(0, 10),
        nota: 'Distribución de cobro (cascada)',
      }).subscribe({
        next: actualizado => {
          const idx = this.empleados.findIndex(x => x.usuarioId === actualizado.usuarioId);
          if (idx !== -1) this.empleados[idx] = actualizado;
          aplicarSiguiente(i + 1);
        },
        error: err => {
          this.confirmandoCascada = false;
          this.notif.errorHttp(err, `No se pudo registrar el pago a ${l.empleadoNombre}`);
        },
      });
    };

    const aplicarRemanente = (): void => {
      this.finanzasService.registrarMovimientoCaja({
        tipo: 'INGRESO', tipoCaja: this.cascadaCaja, categoria: 'COBRO_ODONTOLOGO',
        concepto: 'Remanente de cobro distribuido (cascada)', monto: remanente,
      }).subscribe({
        next: () => finalizar(),
        error: err => {
          this.confirmandoCascada = false;
          this.notif.errorHttp(err, 'Los pagos se registraron, pero no se pudo cargar el remanente a la caja');
        },
      });
    };

    const finalizar = (): void => {
      this.confirmandoCascada = false;
      this.showCascadaModal = false;
      this.cargarResumen();
      this.notif.exito('Distribución del cobro registrada correctamente');
    };

    aplicarSiguiente(0);
  }

  // ── Histórico de pagos ──
  abrirHistorial(e: EmpleadoSueldo): void {
    this.empleadoHistorial = e;
    this.showHistorialModal = true;
    this.loadingHistorial = true;
    this.historial = [];
    this.sueldosService.historialPagos(e.usuarioId).subscribe({
      next: data => { this.historial = data; this.loadingHistorial = false; },
      error: err => { this.loadingHistorial = false; this.notif.errorHttp(err, 'No se pudo cargar el histórico'); },
    });
  }
  cerrarHistorial(): void { this.showHistorialModal = false; this.empleadoHistorial = null; }

  get totalPagadoHistorial(): number {
    return this.historial.reduce((sum, p) => sum + p.monto, 0);
  }

  formatFechaCortaSueldo(iso: string | null): string {
    if (!iso) return '—';
    return new Date(iso).toLocaleDateString('es-AR', { day: '2-digit', month: '2-digit', year: '2-digit' });
  }

  // ── Cuentas corrientes / Ranking morosos ─────────────────────────

  cargarCuentasCorrientes(silencioso = false): void {
    if (!silencioso) this.loadingCuentas = true;
    // true: trae también a los que ya saldaron todo, para que "Todos" y
    // "Solo morosos" muestren listas realmente distintas (ver aplicarFiltroMorosos).
    this.finanzasService.rankingMorosos(true).subscribe({
      next: data => {
        this.cuentasCorrientes = data;
        this.aplicarFiltroMorosos();
        if (!silencioso) this.loadingCuentas = false;
      },
      error: err => {
        if (!silencioso) {
          this.loadingCuentas = false;
          this.notif.errorHttp(err, 'No se pudo cargar el ranking de cuentas corrientes');
        }
      },
    });
  }

  cambiarFiltroMoroso(f: FiltroMorosos): void {
    this.filtroMorosos = f;
    this.aplicarFiltroMorosos();
  }

  /** Aplica filtro + búsqueda sobre el listado completo. */
  aplicarFiltroMorosos(): void {
    let r = [...this.cuentasCorrientes];

    // Filtro por estado
    switch (this.filtroMorosos) {
      case 'MOROSOS': r = r.filter(c => c.totalDeuda > 0); break;
      case 'MAS_30':  r = r.filter(c => c.diasSinPagar > 30); break;
      case 'MAS_60':  r = r.filter(c => c.diasSinPagar > 60); break;
      // TODOS: sin filtro
    }

    // Búsqueda por nombre
    const q = this.busquedaMoroso.trim().toLowerCase();
    if (q) {
      r = r.filter(c => c.odontologoNombre.toLowerCase().includes(q));
    }

    // Pasamos pesos de severidad para que ese sort sea por gravedad, no alfabético
    this.cuentasFiltradas = this.aplicarSort(r, this.sortCuentas, SEVERIDAD_PESO);
  }

  /** Abre el modal de pago para un odontólogo del ranking, sin salir de Finanzas. */
  abrirModalPago(c: CuentaCorrienteOdontologoResponse, event: Event): void {
    event.stopPropagation(); // la fila tiene routerLink al historial; no navegar
    this.odontologoPago = c;
  }

  cerrarModalPago(): void {
    this.odontologoPago = null;
  }

  onPagoRegistrado(): void {
    this.odontologoPago = null;
    this.cargarCuentasCorrientes();
  }

  // ── Stats agregadas del ranking ──────────────────────────────────

  get totalDeudaGlobal(): number {
    return this.cuentasCorrientes.reduce((sum, c) => sum + c.totalDeuda, 0);
  }

  get cantidadMorosos(): number {
    return this.cuentasCorrientes.filter(c => c.totalDeuda > 0).length;
  }

  get cantidadCriticos(): number {
    return this.cuentasCorrientes.filter(c => c.severidad === 'CRITICA').length;
  }

  // ── Datos para los widgets del dashboard de Resumen ──────────────

  /** Top 3 odontólogos con más deuda (para el widget de cuentas). */
  get top3Morosos(): CuentaCorrienteOdontologoResponse[] {
    return [...this.cuentasCorrientes]
      .sort((a, b) => b.totalDeuda - a.totalDeuda)
      .slice(0, 3);
  }

  /** Saldo total de las cajas reales del lab (física + bancaria). */
  get saldoTotalCajas(): number {
    if (!this.resumen) return 0;
    return this.resumen.saldoFisica + this.resumen.saldoBancaria;
  }

  // ── Helpers de presentación para la tabla de cuentas ─────────────

  labelSeveridad(s: SeveridadDeuda): string {
    const m: Record<SeveridadDeuda, string> = {
      AL_DIA: 'Al día',
      BAJA: 'Baja',
      MEDIA: 'Media',
      ALTA: 'Alta',
      CRITICA: 'Crítica',
    };
    return m[s];
  }

  iconoSeveridad(s: SeveridadDeuda): string {
    const m: Record<SeveridadDeuda, string> = {
      AL_DIA: '✓', BAJA: '·', MEDIA: '!', ALTA: '!!', CRITICA: '⚠',
    };
    return m[s];
  }

  formatPrecioMoneda(n: number): string {
    return new Intl.NumberFormat('es-AR', {
      style: 'currency', currency: 'ARS', maximumFractionDigits: 0
    }).format(n);
  }

  formatFechaCorta(iso: string | null): string {
    if (!iso) return '—';
    return new Date(iso).toLocaleDateString('es-AR', { day: '2-digit', month: '2-digit', year: '2-digit' });
  }

  // ─────────────────────────────────────────────────────────────
  // CARGAS
  // ─────────────────────────────────────────────────────────────

  cargarResumen(silencioso = false): void {
    if (!silencioso) { this.loadingResumen = true; this.errorResumen = ''; }
    this.finanzasService.obtenerResumen().subscribe({
      next: data => { this.resumen = data; if (!silencioso) this.loadingResumen = false; },
      error: err => {
        if (!silencioso) {
          this.errorResumen = 'No se pudo cargar el resumen. ¿ms-finanzas está corriendo?';
          this.loadingResumen = false;
        }
        console.error(err);
      },
    });
  }

  cambiarCaja(tipo: TipoCaja): void {
    this.cajaActiva = tipo;
    this.cargarMovimientos();
  }

  cargarMovimientos(silencioso = false): void {
    if (!silencioso) this.loadingMovimientos = true;
    this.finanzasService.movimientosPorCaja(this.cajaActiva).subscribe({
      next: data => {
        this.movimientos = data;
        this.aplicarFiltroMovimientos();
        if (!silencioso) this.loadingMovimientos = false;
      },
      error: err => {
        if (!silencioso) this.loadingMovimientos = false;
        console.error(err);
      },
    });
  }

  // ─────────────────────────────────────────────────────────────
  // STATS POR CAJA SELECCIONADA
  // ─────────────────────────────────────────────────────────────

  get saldoCajaActiva(): number {
    if (!this.resumen) return 0;
    switch (this.cajaActiva) {
      case 'FISICA':       return this.resumen.saldoFisica;
      case 'BANCARIA':     return this.resumen.saldoBancaria;
      case 'COMPENSACION': return this.resumen.saldoCompensacion;
    }
  }

  /** Movimientos de la caja activa filtrados SOLO por período (sin tipo/búsqueda). */
  get movimientosDelPeriodo(): CajaMovimientoResponse[] {
    return this.movimientos.filter(m => this.estaEnPeriodo(m.fechaMovimiento));
  }

  get ingresosCajaActiva(): number {
    return this.movimientosDelPeriodo
      .filter(m => m.tipo === 'INGRESO')
      .reduce((sum, m) => sum + m.monto, 0);
  }

  get egresosCajaActiva(): number {
    return this.movimientosDelPeriodo
      .filter(m => m.tipo === 'EGRESO')
      .reduce((sum, m) => sum + m.monto, 0);
  }

  /** True si la fecha cae dentro del período seleccionado. */
  private estaEnPeriodo(fechaIso: string): boolean {
    if (this.periodoMovimientos === 'TODO') return true;
    const ahora = new Date();
    // fechaMovimiento es un LocalDate ("YYYY-MM-DD", sin hora) — new Date(str)
    // lo interpreta como medianoche UTC, que en Argentina cae en el día
    // anterior. Por eso el filtro "Hoy" nunca traía nada. fechaLocal() lo
    // parsea como medianoche LOCAL en su lugar.
    const fecha = fechaLocal(fechaIso);
    if (isNaN(fecha.getTime())) return true;

    if (this.periodoMovimientos === 'HOY') {
      return fecha.toDateString() === ahora.toDateString();
    }
    const diffDias = (ahora.getTime() - fecha.getTime()) / 86_400_000;
    if (this.periodoMovimientos === 'SEMANA') return diffDias <= 7;
    return diffDias <= 31; // MES
  }

  cambiarPeriodoMovimientos(p: PeriodoMov): void {
    this.periodoMovimientos = p;
    this.aplicarFiltroMovimientos();
  }

  get tieneCompensacionDesbalanceada(): boolean {
    return !!this.resumen && this.resumen.saldoCompensacion !== 0;
  }

  get cajaActivaLabel(): string {
    return this.cajas.find(c => c.tipo === this.cajaActiva)?.label ?? '';
  }

  // ─────────────────────────────────────────────────────────────
  // HELPERS DE VISTA
  // ─────────────────────────────────────────────────────────────

  formatMoney(n: number): string {
    return new Intl.NumberFormat('es-AR', {
      style: 'currency', currency: 'ARS', maximumFractionDigits: 0
    }).format(n);
  }

  formatFecha(iso: string): string {
    return new Date(iso).toLocaleDateString('es-AR',
      { day: '2-digit', month: '2-digit', year: 'numeric' });
  }

  colorSaldo(saldo: number, esCompensacion = false): 'positivo' | 'negativo' | 'neutro' {
    if (esCompensacion) return saldo === 0 ? 'neutro' : 'negativo';
    if (saldo < 0) return 'negativo';
    if (saldo === 0) return 'neutro';
    return 'positivo';
  }

  // ═════════════════ FILTROS Y SORT DE MOVIMIENTOS ═════════════════

  cambiarFiltroMovimientos(f: FiltroMovimientos): void {
    this.filtroMovimientos = f;
    this.aplicarFiltroMovimientos();
  }

  aplicarFiltroMovimientos(): void {
    // Arranca de los movimientos del período seleccionado
    let r = this.movimientosDelPeriodo;

    // Filtro tipo
    if (this.filtroMovimientos !== 'TODOS') {
      r = r.filter(m => m.tipo === this.filtroMovimientos);
    }

    // Búsqueda por concepto o referencia
    const q = this.busquedaMovimiento.trim().toLowerCase();
    if (q) {
      r = r.filter(m =>
        m.concepto.toLowerCase().includes(q) ||
        (m.referencia ?? '').toLowerCase().includes(q) ||
        (m.creadoPor ?? '').toLowerCase().includes(q)
      );
    }

    // Sort
    this.movimientosFiltrados = this.aplicarSort(r, this.sortMov);
  }

  /** Click en un encabezado: si es la misma columna invierte dir; si no, asc por default. */
  ordenarPorMov(campo: keyof CajaMovimientoResponse): void {
    if (this.sortMov.campo === campo) {
      this.sortMov = { campo, dir: this.sortMov.dir === 'asc' ? 'desc' : 'asc' };
    } else {
      this.sortMov = { campo, dir: 'asc' };
    }
    this.aplicarFiltroMovimientos();
  }

  ordenarPorCuenta(campo: keyof CuentaCorrienteOdontologoResponse): void {
    if (this.sortCuentas.campo === campo) {
      this.sortCuentas = { campo, dir: this.sortCuentas.dir === 'asc' ? 'desc' : 'asc' };
    } else {
      this.sortCuentas = { campo, dir: 'desc' }; // default DESC para columnas numéricas
    }
    this.aplicarFiltroMorosos();
  }

  /**
   * Genérico: ordena array por un campo con dirección. Funciona para strings,
   * números y fechas ISO.
   *
   * @param weights mapa opcional valor→peso para campos enum (ej: severidad),
   *   para ordenarlos por gravedad real en lugar de alfabéticamente.
   */
  private aplicarSort<T>(arr: T[], state: SortState<keyof T>, weights?: Record<string, number>): T[] {
    const { campo, dir } = state;
    const mult = dir === 'asc' ? 1 : -1;
    return [...arr].sort((a, b) => {
      const va = a[campo] as unknown;
      const vb = b[campo] as unknown;
      if (va == null && vb == null) return 0;
      if (va == null) return 1;
      if (vb == null) return -1;
      // Campos enum con peso (ej: severidad) → ordenar por gravedad, no alfabético
      if (weights && typeof va === 'string' && typeof vb === 'string'
          && va in weights && vb in weights) {
        return (weights[va] - weights[vb]) * mult;
      }
      if (typeof va === 'number' && typeof vb === 'number') return (va - vb) * mult;
      // Fechas ISO se comparan bien como strings, pero por las dudas:
      const sa = String(va).toLowerCase();
      const sb = String(vb).toLowerCase();
      return sa.localeCompare(sb, 'es') * mult;
    });
  }

  /** Indicador visual del estado del sort en un header. */
  iconoSortMov(campo: keyof CajaMovimientoResponse): string {
    if (this.sortMov.campo !== campo) return '⇅';
    return this.sortMov.dir === 'asc' ? '↑' : '↓';
  }

  iconoSortCuenta(campo: keyof CuentaCorrienteOdontologoResponse): string {
    if (this.sortCuentas.campo !== campo) return '⇅';
    return this.sortCuentas.dir === 'asc' ? '↑' : '↓';
  }

  // ═════════════════════════ EFECTIVO PENDIENTE ═══════════════════════════════

  cargarPendientesEfectivo(silencioso = false): void {
    if (!silencioso) this.loadingPendientes = true;
    this.sueldosService.pendientesEfectivo().subscribe({
      next: data => { this.pendientesEfectivo = data; if (!silencioso) this.loadingPendientes = false; },
      error: err => {
        if (!silencioso) {
          this.loadingPendientes = false;
          this.notif.errorHttp(err, 'No se pudieron cargar los efectivos pendientes');
        }
      },
    });
  }

  confirmarEfectivo(id: number): void {
    this.sueldosService.confirmarEfectivo(id).subscribe({
      next: reg => {
        this.pendientesEfectivo = this.pendientesEfectivo.filter(r => r.id !== id);
        this.notif.exito(`Efectivo confirmado → ${reg.estado === 'REGISTRADO' ? reg.receptorResuelto ?? reg.receptorNombre : '(no resuelto)'}`);
      },
      error: err => this.notif.errorHttp(err, 'No se pudo confirmar'),
    });
  }

  abrirRechazo(id: number): void {
    this.rechazandoId = id;
    this.motivoRechazo = '';
  }

  rechazarEfectivo(): void {
    if (this.rechazandoId == null) return;
    this.sueldosService.rechazarEfectivo(this.rechazandoId, this.motivoRechazo || undefined).subscribe({
      next: () => {
        this.pendientesEfectivo = this.pendientesEfectivo.filter(r => r.id !== this.rechazandoId);
        this.rechazandoId = null;
        this.notif.alerta('Efectivo rechazado');
      },
      error: err => this.notif.errorHttp(err, 'No se pudo rechazar'),
    });
  }

  formatHora(iso: string): string {
    return new Date(iso).toLocaleString('es-AR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
  }

}
