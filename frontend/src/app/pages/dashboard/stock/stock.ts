import { Component, OnInit, inject, DestroyRef } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  StockService, MaterialResponse, MaterialRequest, CategoriaMaterial,
  TipoMovimiento, MovimientoRequest,
} from '../../../services/stock.service';
import { NotificationService } from '../../../services/notification.service';
import { iniciarPolling } from '../../../shared/poll.util';

type FiltroCategoria = CategoriaMaterial | 'TODOS';
type SortDir = 'asc' | 'desc';
interface SortStock { campo: keyof MaterialResponse; dir: SortDir; }

@Component({
  selector: 'app-stock',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './stock.html',
  styleUrls: ['./stock.css'],
})
export class StockComponent implements OnInit {

  materiales: MaterialResponse[] = [];
  filtrados: MaterialResponse[] = [];
  loading = false;
  error = '';

  // Filtros
  busqueda = '';
  categoriaActiva: FiltroCategoria = 'TODOS';
  soloBajoStock = false;

  // Ordenamiento de la tabla
  sortStock: SortStock = { campo: 'nombre', dir: 'asc' };

  // Modal CRUD
  showModal = false;
  editMode = false;
  saving = false;
  materialEditandoId: number | null = null;

  form: MaterialRequest = this.formVacio();

  // Modal de movimiento (entrada/salida/ajuste)
  showMovModal = false;
  movMaterial: MaterialResponse | null = null;
  movForm: { tipo: TipoMovimiento; cantidad: number | null; motivo: string } = {
    tipo: 'ENTRADA', cantidad: null, motivo: '',
  };
  savingMov = false;

  // Confirmar desactivar
  desactivarConfirmId: number | null = null;

  readonly categorias: { valor: FiltroCategoria; label: string }[] = [
    { valor: 'TODOS',       label: 'Todos'        },
    { valor: 'YESO',        label: 'Yeso'         },
    { valor: 'CERAMICA',    label: 'Cerámica'     },
    { valor: 'PORCELANA',   label: 'Porcelana'    },
    { valor: 'ACRILICO',    label: 'Acrílico'     },
    { valor: 'METAL',       label: 'Metal'        },
    { valor: 'RESINA',      label: 'Resina'       },
    { valor: 'ALAMBRE',     label: 'Alambre'      },
    { valor: 'ZIRCONIA',    label: 'Zirconia'     },
    { valor: 'CERA',        label: 'Cera'         },
    { valor: 'ADHESIVO',    label: 'Adhesivo'     },
    { valor: 'HERRAMIENTA', label: 'Herramienta'  },
    { valor: 'CONSUMIBLE',  label: 'Consumible'   },
    { valor: 'OTRO',        label: 'Otro'         },
  ];

  readonly categoriasForm: { valor: CategoriaMaterial; label: string }[] =
    this.categorias.filter(c => c.valor !== 'TODOS') as { valor: CategoriaMaterial; label: string }[];

  readonly tiposMovimiento: { valor: TipoMovimiento; label: string; desc: string }[] = [
    { valor: 'ENTRADA', label: 'Entrada',  desc: 'Sumar stock — compra, reposición'   },
    { valor: 'SALIDA',  label: 'Salida',   desc: 'Restar stock — consumo, descarte'   },
    { valor: 'AJUSTE',  label: 'Ajuste',   desc: 'Setear el stock al valor indicado'  },
  ];

  private notif = inject(NotificationService);
  private destroyRef = inject(DestroyRef);

  constructor(private stockService: StockService) {}

  ngOnInit(): void {
    this.cargar();
    iniciarPolling(() => this.cargar(true), this.destroyRef);
  }

  // ─────────────────────────────────────────────────────────────
  // CARGA Y FILTROS
  // ─────────────────────────────────────────────────────────────

  private cargar(silencioso = false): void {
    if (silencioso && (this.showModal || this.showMovModal || this.desactivarConfirmId != null)) return;
    if (!silencioso) { this.loading = true; this.error = ''; }
    this.stockService.listarActivos().subscribe({
      next: data => {
        this.materiales = data.sort((a, b) => a.nombre.localeCompare(b.nombre));
        this.filtrar();
        if (!silencioso) this.loading = false;
      },
      error: err => {
        if (!silencioso) {
          this.error = 'No se pudo cargar el stock. ¿ms-stock está corriendo?';
          this.loading = false;
        }
        console.error(err);
      },
    });
  }

  filtrar(): void {
    const q = this.busqueda.trim().toLowerCase();
    const filtrados = this.materiales.filter(m => {
      if (this.soloBajoStock && !m.bajoStock) return false;
      if (this.categoriaActiva !== 'TODOS' && m.categoria !== this.categoriaActiva) return false;
      if (q && !m.nombre.toLowerCase().includes(q) &&
              !(m.descripcion ?? '').toLowerCase().includes(q) &&
              !(m.proveedor ?? '').toLowerCase().includes(q)) return false;
      return true;
    });
    this.filtrados = this.ordenar(filtrados);
  }

  /** Click en un encabezado: misma columna invierte dir, otra arranca asc. */
  ordenarPor(campo: keyof MaterialResponse): void {
    if (this.sortStock.campo === campo) {
      this.sortStock = { campo, dir: this.sortStock.dir === 'asc' ? 'desc' : 'asc' };
    } else {
      this.sortStock = { campo, dir: 'asc' };
    }
    this.filtrar();
  }

  private ordenar(arr: MaterialResponse[]): MaterialResponse[] {
    const { campo, dir } = this.sortStock;
    const mult = dir === 'asc' ? 1 : -1;
    return [...arr].sort((a, b) => {
      const va = a[campo] as unknown;
      const vb = b[campo] as unknown;
      if (va == null && vb == null) return 0;
      if (va == null) return 1;
      if (vb == null) return -1;
      if (typeof va === 'number' && typeof vb === 'number') return (va - vb) * mult;
      if (typeof va === 'boolean' && typeof vb === 'boolean') return (Number(va) - Number(vb)) * mult;
      return String(va).toLowerCase().localeCompare(String(vb).toLowerCase(), 'es') * mult;
    });
  }

  iconoSort(campo: keyof MaterialResponse): string {
    if (this.sortStock.campo !== campo) return '⇅';
    return this.sortStock.dir === 'asc' ? '↑' : '↓';
  }

  setCategoriaActiva(cat: FiltroCategoria): void {
    this.categoriaActiva = cat;
    this.filtrar();
  }

  countByCategoria(cat: FiltroCategoria): number {
    if (cat === 'TODOS') return this.materiales.length;
    return this.materiales.filter(m => m.categoria === cat).length;
  }

  // Stats del header
  get totalMateriales(): number { return this.materiales.length; }
  get totalBajoStock(): number { return this.materiales.filter(m => m.bajoStock).length; }
  get totalAgotados(): number { return this.materiales.filter(m => m.stockActual === 0).length; }

  // ─────────────────────────────────────────────────────────────
  // MODAL CRUD
  // ─────────────────────────────────────────────────────────────

  abrirCrear(): void {
    this.editMode = false;
    this.materialEditandoId = null;
    this.form = this.formVacio();
    this.showModal = true;
  }

  abrirEditar(m: MaterialResponse): void {
    this.editMode = true;
    this.materialEditandoId = m.id;
    this.form = {
      nombre: m.nombre,
      descripcion: m.descripcion ?? '',
      categoria: m.categoria,
      stockActual: m.stockActual,
      stockMinimo: m.stockMinimo,
      unidadMedida: m.unidadMedida,
      precioUnitario: m.precioUnitario,
      proveedor: m.proveedor ?? '',
      descuentaStock: m.descuentaStock,
    };
    this.showModal = true;
  }

  cerrarModal(): void {
    this.showModal = false;
  }

  private formVacio(): MaterialRequest {
    return {
      nombre: '',
      descripcion: '',
      categoria: 'CONSUMIBLE',
      stockActual: 0,
      stockMinimo: 0,
      unidadMedida: '',
      precioUnitario: null,
      proveedor: '',
      descuentaStock: true,
    };
  }

  get formValido(): boolean {
    return !!(
      this.form.nombre?.trim() &&
      this.form.categoria &&
      this.form.unidadMedida?.trim() &&
      this.form.stockActual >= 0 &&
      this.form.stockMinimo >= 0
    );
  }

  guardar(): void {
    if (!this.formValido) return;
    this.saving = true;

    const op$ = this.editMode && this.materialEditandoId
      ? this.stockService.actualizar(this.materialEditandoId, this.form)
      : this.stockService.crear(this.form);

    op$.subscribe({
      next: res => {
        if (this.editMode) {
          const idx = this.materiales.findIndex(m => m.id === res.id);
          if (idx !== -1) this.materiales[idx] = res;
          this.notif.exito(`Material "${res.nombre}" actualizado`);
        } else {
          this.materiales.push(res);
          this.notif.exito(`Material "${res.nombre}" creado`);
        }
        this.materiales.sort((a, b) => a.nombre.localeCompare(b.nombre));
        this.filtrar();
        this.saving = false;
        this.cerrarModal();
      },
      error: err => {
        this.saving = false;
        this.notif.errorHttp(err, 'No se pudo guardar el material');
      },
    });
  }

  // ─────────────────────────────────────────────────────────────
  // MOVIMIENTOS DE STOCK
  // ─────────────────────────────────────────────────────────────

  abrirMovimiento(m: MaterialResponse): void {
    this.movMaterial = m;
    this.movForm = { tipo: 'ENTRADA', cantidad: null, motivo: '' };
    this.showMovModal = true;
  }

  cerrarMovimiento(): void {
    this.showMovModal = false;
    this.movMaterial = null;
  }

  get movValido(): boolean {
    return !!(this.movMaterial && this.movForm.cantidad != null && this.movForm.cantidad >= 0);
  }

  /** Preview del stock resultante para que el user vea qué va a pasar. */
  get movStockResultante(): number | null {
    if (!this.movMaterial || this.movForm.cantidad == null) return null;
    switch (this.movForm.tipo) {
      case 'ENTRADA': return this.movMaterial.stockActual + this.movForm.cantidad;
      case 'SALIDA':  return this.movMaterial.stockActual - this.movForm.cantidad;
      case 'AJUSTE':  return this.movForm.cantidad;
    }
  }

  confirmarMovimiento(): void {
    if (!this.movValido || !this.movMaterial) return;
    this.savingMov = true;
    const req: MovimientoRequest = {
      materialId: this.movMaterial.id,
      tipo: this.movForm.tipo,
      cantidad: this.movForm.cantidad!,
      motivo: this.movForm.motivo?.trim() || null,
    };
    this.stockService.registrarMovimiento(req).subscribe({
      next: res => {
        const idx = this.materiales.findIndex(m => m.id === res.id);
        if (idx !== -1) this.materiales[idx] = res;
        this.filtrar();
        this.savingMov = false;
        this.cerrarMovimiento();
        const tipoLabel = req.tipo === 'ENTRADA' ? 'Entrada' : req.tipo === 'SALIDA' ? 'Salida' : 'Ajuste';
        this.notif.exito(`${tipoLabel} registrada en "${res.nombre}". Nuevo stock: ${res.stockActual} ${res.unidadMedida}`);
      },
      error: err => {
        this.savingMov = false;
        this.notif.errorHttp(err, 'No se pudo registrar el movimiento');
      },
    });
  }

  // ─────────────────────────────────────────────────────────────
  // DESACTIVAR
  // ─────────────────────────────────────────────────────────────

  pedirConfirmDesactivar(id: number): void {
    this.desactivarConfirmId = id;
  }
  abortarDesactivar(): void {
    this.desactivarConfirmId = null;
  }
  confirmarDesactivar(id: number): void {
    const nombre = this.materiales.find(m => m.id === id)?.nombre ?? 'material';
    this.stockService.eliminar(id).subscribe({
      next: () => {
        this.materiales = this.materiales.filter(m => m.id !== id);
        this.desactivarConfirmId = null;
        this.filtrar();
        this.notif.alerta(`"${nombre}" desactivado`);
      },
      error: err => {
        this.desactivarConfirmId = null;
        this.notif.errorHttp(err, 'No se pudo desactivar el material');
      },
    });
  }

  // ─────────────────────────────────────────────────────────────
  // HELPERS DE VISTA
  // ─────────────────────────────────────────────────────────────

  formatMoney(n: number | null): string {
    if (n == null) return '—';
    return new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 0 }).format(n);
  }

  formatStock(n: number, unidad: string): string {
    return `${new Intl.NumberFormat('es-AR', { maximumFractionDigits: 2 }).format(n)} ${unidad}`;
  }

  estadoStock(m: MaterialResponse): { clase: string; texto: string } {
    if (m.stockActual === 0) return { clase: 'agotado', texto: 'Agotado' };
    if (m.bajoStock)         return { clase: 'bajo',    texto: 'Bajo mínimo' };
    return { clase: 'ok', texto: 'OK' };
  }

  /** Porcentaje del stock actual respecto al mínimo (cap a 100 para la barra). */
  progressPct(m: MaterialResponse): number {
    if (m.stockMinimo <= 0) return 100;
    return Math.min(100, (m.stockActual / m.stockMinimo) * 100);
  }

  get modalTitle(): string {
    return this.editMode ? 'Editar material' : 'Nuevo material';
  }
}
