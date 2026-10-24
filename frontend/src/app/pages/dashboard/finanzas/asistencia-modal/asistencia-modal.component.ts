import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import {
  AsistenciaMes, DiaAsistencia, EmpleadoSueldo, MovimientoSueldo, SueldosService, TipoDia, TipoMovimientoSueldo,
} from '../../../../services/sueldos.service';
import { NotificationService } from '../../../../services/notification.service';
import { fechaLocal, hoyComoLocalDate } from '../../../../services/date-utils';

interface TipoDiaUI {
  valor: TipoDia;
  label: string;
  ayuda: string;
}

/**
 * Calendario de asistencia de un empleado: se eligen los días (sueltos, o en rango con «Elegir rango» o Mayús) y se les pone
 * qué pasó (trabajó, ausente, vacaciones, feriado, licencia paga). Cada marca mueve el sueldo por días reales
 * del mes: el backend corrige el saldo solo si el día ya estaba devengado.
 */
@Component({
  selector: 'app-asistencia-modal',
  imports: [ReactiveFormsModule],
  templateUrl: './asistencia-modal.component.html',
  styleUrl: './asistencia-modal.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AsistenciaModalComponent implements OnInit {
  readonly empleado = input.required<EmpleadoSueldo>();
  readonly cerrar = output<void>();
  /** Se emite cuando una marca pudo mover el saldo: quien abrió el modal refresca la tarjeta. */
  readonly cambio = output<void>();

  private readonly sueldos = inject(SueldosService);
  private readonly notif = inject(NotificationService);

  readonly hoy = hoyComoLocalDate();
  readonly diasSemana = ['L', 'M', 'X', 'J', 'V', 'S', 'D'];
  readonly tipos: TipoDiaUI[] = [
    { valor: 'TRABAJADO',     label: 'Trabajó',       ayuda: 'Se le paga el día' },
    { valor: 'AUSENTE',       label: 'Ausente',       ayuda: 'Faltó sin goce: no se le paga' },
    { valor: 'VACACIONES',    label: 'Vacaciones',    ayuda: 'Se paga y descuenta del cupo anual' },
    { valor: 'FERIADO',       label: 'Feriado',       ayuda: 'Se le paga el día' },
    { valor: 'LICENCIA_PAGA', label: 'Licencia paga', ayuda: 'Enfermedad, estudio, duelo… se paga' },
  ];

  readonly vista = signal<'calendario' | 'movimientos'>('calendario');
  readonly mes = signal(this.hoy.slice(0, 7));
  readonly data = signal<AsistenciaMes | null>(null);
  readonly cargando = signal(false);
  readonly guardando = signal(false);
  readonly nota = new FormControl('', { nonNullable: true });

  readonly seleccion = signal<ReadonlySet<string>>(new Set<string>());
  private ancla: string | null = null;

  /**
   * Rango con el dedo: un celular no tiene tecla Mayús. Con el modo activo, el primer toque marca el inicio y el
   * segundo el final; al cerrarse el rango vuelve solo a la selección día por día.
   */
  readonly modoRango = signal(false);
  readonly inicioRango = signal<string | null>(null);
  /** Con mouse además se puede mantener Mayús; con el dedo ese consejo no sirve y se omite. */
  private readonly hayMouse = typeof matchMedia === 'function' && matchMedia('(pointer: fine)').matches;
  readonly ayudaSeleccion = computed(() => {
    if (this.modoRango()) return this.inicioRango() ? 'Ahora tocá el último día del rango.' : 'Tocá el primer día del rango.';
    return 'Tocá los días que quieras, o «Elegir rango» para marcar desde un día hasta otro.'
      + (this.hayMouse ? ' También con Mayús.' : '');
  });

  readonly movimientos = signal<MovimientoSueldo[]>([]);
  readonly cargandoMovimientos = signal(false);

  readonly tituloMes = computed(() => {
    const [a, m] = this.mes().split('-').map(Number);
    const t = new Date(a, m - 1, 1).toLocaleDateString('es-AR', { month: 'long', year: 'numeric' });
    return t.charAt(0).toUpperCase() + t.slice(1);
  });

  /** Cuántas celdas vacías van antes del día 1 (la semana empieza el lunes). */
  readonly celdasPrevias = computed(() => {
    const [a, m] = this.mes().split('-').map(Number);
    return Array.from({ length: (new Date(a, m - 1, 1).getDay() + 6) % 7 });
  });

  /** Quien cobra por día solo cobra los días marcados; el resto cobra todos salvo las ausencias. */
  readonly pieDelResumen = computed(() => this.data()?.frecuencia === 'DIARIO'
    ? 'Los días sin marca no se pagan: marcá como Trabajó los días que vino. Si marcás un día que ya se devengó, el saldo se corrige en el momento.'
    : 'Los días que todavía no pasaron figuran como trabajados (salvo que los marques). Si marcás un día que ya se devengó, el saldo se corrige en el momento.');

  readonly explicacion = computed(() => {
    switch (this.data()?.frecuencia) {
      case 'DIARIO':    return 'Cobra por día trabajado: solo se le paga los días que marques como Trabajó, Vacaciones, Feriado o Licencia paga.';
      case 'SEMANAL':   return 'Cobra su monto por semana: se le paga todos los días, salvo los que marques como Ausente.';
      case 'QUINCENAL': return 'Cobra su monto por quincena (1 al 15 y 16 al último día): se le paga todos los días, salvo los Ausente.';
      default:          return 'Cobra su monto por mes calendario: se le paga todos los días, salvo los que marques como Ausente.';
    }
  });

  ngOnInit(): void {
    this.cargar();
  }

  // ── Carga ─────────────────────────────────────────────────────────

  cargar(): void {
    this.cargando.set(true);
    this.sueldos.asistenciaMes(this.empleado().usuarioId, this.mes()).subscribe({
      next: d => { this.data.set(d); this.cargando.set(false); },
      error: err => { this.cargando.set(false); this.notif.errorHttp(err, 'No se pudo cargar el calendario'); },
    });
  }

  private cargarMovimientos(): void {
    this.cargandoMovimientos.set(true);
    this.sueldos.movimientos(this.empleado().usuarioId, 100).subscribe({
      next: m => { this.movimientos.set(m); this.cargandoMovimientos.set(false); },
      error: err => { this.cargandoMovimientos.set(false); this.notif.errorHttp(err, 'No se pudo cargar el libro de sueldos'); },
    });
  }

  cambiarVista(v: 'calendario' | 'movimientos'): void {
    this.vista.set(v);
    if (v === 'movimientos') this.cargarMovimientos();
  }

  // ── Navegación de meses ───────────────────────────────────────────

  private moverMes(delta: number): void {
    const [a, m] = this.mes().split('-').map(Number);
    const d = new Date(a, m - 1 + delta, 1);
    this.mes.set(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`);
    this.limpiarSeleccion();
    this.cargar();
  }
  mesAnterior(): void { this.moverMes(-1); }
  mesSiguiente(): void { this.moverMes(1); }
  irAHoy(): void {
    this.mes.set(this.hoy.slice(0, 7));
    this.limpiarSeleccion();
    this.cargar();
  }

  // ── Selección ─────────────────────────────────────────────────────

  clickDia(d: DiaAsistencia, ev: MouseEvent): void {
    if (!d.enRelacion || this.guardando()) return;
    const data = this.data();
    const nueva = new Set(this.seleccion());
    const inicio = this.inicioRango();
    const cierraRango = this.modoRango() && inicio !== null;
    if ((ev.shiftKey && this.ancla || cierraRango) && data) {
      const desdeCualquiera = cierraRango ? inicio! : this.ancla!;
      const [desde, hasta] = desdeCualquiera <= d.fecha ? [desdeCualquiera, d.fecha] : [d.fecha, desdeCualquiera];
      data.dias.filter(x => x.enRelacion && x.fecha >= desde && x.fecha <= hasta).forEach(x => nueva.add(x.fecha));
      if (cierraRango) { this.modoRango.set(false); this.inicioRango.set(null); }
    } else if (this.modoRango()) {
      nueva.add(d.fecha);
      this.inicioRango.set(d.fecha);
    } else if (nueva.has(d.fecha)) {
      nueva.delete(d.fecha);
    } else {
      nueva.add(d.fecha);
    }
    this.seleccion.set(nueva);
    this.ancla = d.fecha;
  }

  alternarRango(): void {
    this.modoRango.update(v => !v);
    this.inicioRango.set(null);
  }

  seleccionarLunesAViernes(): void {
    const data = this.data();
    if (!data) return;
    const nueva = new Set(this.seleccion());
    data.dias
      .filter(d => d.enRelacion && fechaLocal(d.fecha).getDay() >= 1 && fechaLocal(d.fecha).getDay() <= 5)
      .forEach(d => nueva.add(d.fecha));
    this.seleccion.set(nueva);
  }

  seleccionarTodoElMes(): void {
    const data = this.data();
    if (!data) return;
    const nueva = new Set(this.seleccion());
    data.dias.filter(d => d.enRelacion).forEach(d => nueva.add(d.fecha));
    this.seleccion.set(nueva);
  }

  limpiarSeleccion(): void {
    this.seleccion.set(new Set<string>());
    this.ancla = null;
    this.modoRango.set(false);
    this.inicioRango.set(null);
  }

  // ── Marcar ────────────────────────────────────────────────────────

  aplicar(tipo: TipoDia | null): void {
    if (this.seleccion().size === 0 || this.guardando()) return;
    this.guardando.set(true);
    this.sueldos.marcarAsistencia(this.empleado().usuarioId, {
      fechas: [...this.seleccion()].sort(),
      tipo,
      nota: this.nota.value.trim() || null,
    }).subscribe({
      next: r => {
        this.guardando.set(false);
        const dias = `${r.diasModificados} ${r.diasModificados === 1 ? 'día' : 'días'}`;
        if (r.diasModificados === 0) {
          this.notif.alerta('Esos días ya tenían esa marca');
        } else if (r.ajuste !== 0) {
          this.notif.exito(`${dias} actualizados · el saldo ${r.ajuste > 0 ? 'sube' : 'baja'} ${this.dinero(Math.abs(r.ajuste))}`);
        } else {
          this.notif.exito(`${dias} actualizados`);
        }
        r.advertencias.forEach(a => this.notif.alerta(a));
        this.nota.reset('');
        this.limpiarSeleccion();
        this.cambio.emit();
        this.cargar();
      },
      error: err => { this.guardando.set(false); this.notif.errorHttp(err, 'No se pudo guardar la asistencia'); },
    });
  }

  // ── Presentación ──────────────────────────────────────────────────

  claseDia(d: DiaAsistencia): string {
    const c = ['dia'];
    if (!d.enRelacion) c.push('fuera');
    else if (d.tipo) c.push('t-' + d.tipo);
    else c.push(d.sePaga ? 'comun-paga' : 'comun-nopaga');
    if (d.fecha === this.hoy) c.push('hoy');
    if (this.seleccion().has(d.fecha)) c.push('sel');
    return c.join(' ');
  }

  numeroDia(d: DiaAsistencia): number {
    return Number(d.fecha.slice(8, 10));
  }

  /** Solo los días marcados llevan rótulo: los comunes (con o sin cobro) se distinguen por el fondo. */
  etiquetaDia(d: DiaAsistencia): string {
    return d.enRelacion && d.tipo ? this.etiqueta(d.tipo) : '';
  }

  /** Para la celda del calendario en pantallas angostas: "VACACIONES" no entra en una celda de 42 px. */
  etiquetaCorta(d: DiaAsistencia): string {
    if (!d.enRelacion || !d.tipo) return '';
    const cortas: Record<TipoDia, string> = { TRABAJADO: 'Trab.', AUSENTE: 'Aus.', VACACIONES: 'Vac.', FERIADO: 'Fer.', LICENCIA_PAGA: 'Lic.' };
    return cortas[d.tipo];
  }

  etiqueta(t: TipoDia): string {
    return this.tipos.find(x => x.valor === t)?.label ?? t;
  }

  ariaDia(d: DiaAsistencia): string {
    const fecha = fechaLocal(d.fecha).toLocaleDateString('es-AR', { weekday: 'long', day: 'numeric', month: 'long' });
    if (!d.enRelacion) return `${fecha}: fuera del período de empleo`;
    return `${fecha}: ${d.tipo ? this.etiqueta(d.tipo) : (d.sePaga ? 'día común, se paga' : 'sin marca, no se paga')}`;
  }

  dinero(n: number): string {
    return new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 2 }).format(n);
  }

  fechaCorta(iso: string): string {
    return fechaLocal(iso).toLocaleDateString('es-AR', { day: '2-digit', month: '2-digit' });
  }

  etiquetaMovimiento(t: TipoMovimientoSueldo): string {
    switch (t) {
      case 'DEVENGO':           return 'Devengo';
      case 'AJUSTE_ASISTENCIA': return 'Ajuste por asistencia';
      case 'AJUSTE_MANUAL':     return 'Ajuste manual';
      case 'PAGO':              return 'Pago';
    }
  }
}
