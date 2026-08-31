import { Component, OnInit, inject, DestroyRef } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  CatalogoService, TipoTrabajoResponse, TipoTrabajoRequest,
  IngredienteRecetaRequest, IngredienteRecetaResponse,
} from '../../../services/catalogo.service';
import { StockService, MaterialResponse } from '../../../services/stock.service';
import { NotificationService } from '../../../services/notification.service';
import { iniciarPolling } from '../../../shared/poll.util';

export type Categoria = 'FIJA' | 'REMOVIBLE' | 'ORTODONCIA' | 'ATM' | 'PERSONALIZADO';

/** Modelo local de la vista — alinea con TipoTrabajoResponse del back */
export interface TipoTrabajo {
  id: number;
  nombre: string;
  descripcion: string;
  precio: number;
  categoria: Categoria;
  tiempoEstimadoDias: number;
  foto?: string;       // mapea a fotoUrl del backend
  receta: IngredienteRecetaResponse[];
}

/** Item en el form de receta con estado del autocomplete buscable */
interface RecetaFormItem {
  materialId: number | null;
  materialNombre: string;
  cantidad: number | null;
  unidad: string;
  notas: string;
  // Autocomplete state
  searchQuery: string;     // texto que el usuario está tipeando
  dropdownOpen: boolean;   // si el dropdown está visible
}

@Component({
  selector: 'app-catalogo',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './catalogo.html',
  styleUrls: ['./catalogo.css']
})
export class CatalogoComponent implements OnInit {

  trabajos: TipoTrabajo[]  = [];
  filtrados: TipoTrabajo[] = [];

  loading = false;
  error   = '';

  searchQuery      = '';
  categoriaActiva: Categoria | 'TODOS' = 'TODOS';

  showModal  = false;
  editMode   = false;
  saving     = false;
  deleteConfirmId: number | null = null;

  // Modal de detalle (vista read-only)
  detalleAbierto: TipoTrabajo | null = null;

  editandoPrecioId: number | null = null;
  precioTemporal   = 0;

  form: Partial<TipoTrabajo> = {};
  fotoPreview?: string;
  arrastrando = false;

  // ── Receta ─────────────────────────────────────────────────────
  materiales: MaterialResponse[] = [];
  loadingMateriales = false;
  formReceta: RecetaFormItem[] = [];

  readonly categorias: { valor: Categoria | 'TODOS'; label: string }[] = [
    { valor: 'TODOS',         label: 'Todos'              },
    { valor: 'FIJA',          label: 'Prótesis Fija'      },
    { valor: 'REMOVIBLE',     label: 'Removible'          },
    { valor: 'ORTODONCIA',    label: 'Ortodoncia'         },
    { valor: 'ATM',           label: 'ATM'                },
    { valor: 'PERSONALIZADO', label: 'Personalizado'      },
  ];

  readonly categoriasForm: { valor: Categoria; label: string }[] = [
    { valor: 'FIJA',          label: 'Prótesis Fija'      },
    { valor: 'REMOVIBLE',     label: 'Prótesis Removible' },
    { valor: 'ORTODONCIA',    label: 'Ortodoncia'         },
    { valor: 'ATM',           label: 'ATM'                },
    { valor: 'PERSONALIZADO', label: 'Personalizado'      },
  ];

  private notif = inject(NotificationService);
  private destroyRef = inject(DestroyRef);

  constructor(
    private catService: CatalogoService,
    private stockService: StockService,
  ) {}

  ngOnInit() {
    this.cargar();
    this.cargarMateriales();
    iniciarPolling(() => this.cargar(true), this.destroyRef);
  }

  /** No pisar el catálogo mientras hay un modal o una edición inline en curso. */
  private get hayEdicionEnCurso(): boolean {
    return this.showModal || this.editandoPrecioId != null || this.deleteConfirmId != null;
  }

  private cargarMateriales() {
    this.loadingMateriales = true;
    this.stockService.listarActivos().subscribe({
      next: m => { this.materiales = m; this.loadingMateriales = false; },
      error: err => {
        console.error('No se pudo cargar el stock:', err);
        this.loadingMateriales = false;
      },
    });
  }

  // ── CARGA DESDE EL BACKEND ─────────────────────────────────────
  private cargar(silencioso = false) {
    if (silencioso && this.hayEdicionEnCurso) return;
    if (!silencioso) { this.loading = true; this.error = ''; }
    this.catService.listar().subscribe({
      next: (data) => {
        this.trabajos = data.map(this.mapear);
        this.filtrar();
        if (!silencioso) this.loading = false;
      },
      error: (err) => {
        if (!silencioso) {
          this.error   = 'No se pudo cargar el catálogo. Verificá que ms-catalogo esté corriendo.';
          this.loading = false;
        }
        console.error('Error al cargar catálogo:', err);
      }
    });
  }

  /** Mapea TipoTrabajoResponse del backend al modelo local de la vista */
  private mapear = (r: TipoTrabajoResponse): TipoTrabajo => ({
    id:                r.id,
    nombre:            r.nombre,
    descripcion:       r.descripcion ?? '',
    precio:            r.precio ?? 0,
    categoria:         r.categoria,
    tiempoEstimadoDias: r.tiempoEstimadoDias ?? 0,
    foto:              r.fotoUrl ?? undefined,
    receta:            r.receta ?? [],
  });

  // ── FILTRADO LOCAL ─────────────────────────────────────────────
  filtrar() {
    let r = [...this.trabajos];
    if (this.categoriaActiva !== 'TODOS') {
      r = r.filter(t => t.categoria === this.categoriaActiva);
    }
    if (this.searchQuery.trim()) {
      const q = this.searchQuery.toLowerCase();
      r = r.filter(t =>
        t.nombre.toLowerCase().includes(q) ||
        t.descripcion.toLowerCase().includes(q)
      );
    }
    this.filtrados = r;
  }

  setCategoriaActiva(cat: Categoria | 'TODOS') {
    this.categoriaActiva = cat;
    this.filtrar();
  }

  // ── STATS ──────────────────────────────────────────────────────
  get totalItems(): number { return this.trabajos.length; }

  get promedioPrecios(): number {
    const con = this.trabajos.filter(t => t.precio > 0);
    if (!con.length) return 0;
    return Math.round(con.reduce((a, t) => a + t.precio, 0) / con.length);
  }

  get totalCategorias(): number {
    return new Set(this.trabajos.map(t => t.categoria)).size;
  }

  countByCategoria(cat: Categoria | 'TODOS'): number {
    if (cat === 'TODOS') return this.trabajos.length;
    return this.trabajos.filter(t => t.categoria === cat).length;
  }

  // ── MODAL CRUD ─────────────────────────────────────────────────
  abrirCrear() {
    this.editMode    = false;
    this.form        = { categoria: 'FIJA', tiempoEstimadoDias: 5, precio: 0 };
    this.fotoPreview = undefined;
    this.formReceta  = [];
    this.showModal   = true;
  }

  // ── DETALLE (read-only) ────────────────────────────────────────
  abrirDetalle(trabajo: TipoTrabajo) {
    this.detalleAbierto = trabajo;
  }

  cerrarDetalle() {
    this.detalleAbierto = null;
  }

  editarDesdeDetalle() {
    if (this.detalleAbierto) {
      const t = this.detalleAbierto;
      this.detalleAbierto = null;
      this.abrirEditar(t);
    }
  }

  abrirEditar(trabajo: TipoTrabajo) {
    this.editMode    = true;
    this.form        = { ...trabajo };
    this.fotoPreview = trabajo.foto;
    this.formReceta  = trabajo.receta.map(r => ({
      materialId:     r.materialId,
      materialNombre: r.materialNombre,
      cantidad:       r.cantidad,
      unidad:         r.unidad ?? '',
      notas:          r.notas ?? '',
      searchQuery:    r.materialNombre,
      dropdownOpen:   false,
    }));
    this.showModal   = true;
  }

  cerrarModal() {
    this.showModal   = false;
    this.form        = {};
    this.fotoPreview = undefined;
    this.formReceta  = [];
  }

  get formValido(): boolean {
    if (!(this.form.nombre?.trim() && this.form.categoria && this.form.precio != null)) return false;
    // Cada item de receta debe tener materialId + cantidad > 0
    for (const r of this.formReceta) {
      if (!r.materialId || r.cantidad == null || r.cantidad <= 0) return false;
    }
    return true;
  }

  // ── Manejo de la receta ───────────────────────────────────────
  agregarIngrediente() {
    this.formReceta.push({
      materialId: null, materialNombre: '', cantidad: null, unidad: '', notas: '',
      searchQuery: '', dropdownOpen: false,  // se abre solo cuando el user clickea el input
    });
  }

  eliminarIngrediente(idx: number) {
    this.formReceta.splice(idx, 1);
  }

  // ── Autocomplete de materiales ────────────────────────────────

  /** Materiales filtrados por la búsqueda de la fila, excluyendo los ya elegidos. */
  materialesFiltrados(idx: number): MaterialResponse[] {
    const item = this.formReceta[idx];
    const q = (item.searchQuery ?? '').trim().toLowerCase();
    const yaElegidos = new Set(
      this.formReceta
        .filter((_, i) => i !== idx)
        .map(r => r.materialId)
        .filter(id => id != null) as number[]
    );
    return this.materiales
      .filter(m => !yaElegidos.has(m.id))
      .filter(m => !q || m.nombre.toLowerCase().includes(q)
                       || (m.categoria ?? '').toLowerCase().includes(q))
      .slice(0, 10);
  }

  abrirDropdownMaterial(idx: number) {
    this.formReceta[idx].dropdownOpen = true;
  }

  cerrarDropdownMaterialConDelay(idx: number) {
    // Pequeño delay para que el click en una opción se procese antes de cerrar
    setTimeout(() => {
      if (this.formReceta[idx]) this.formReceta[idx].dropdownOpen = false;
    }, 180);
  }

  /** Cuando el usuario tipea para buscar, limpiamos la selección actual. */
  onSearchInput(idx: number) {
    const item = this.formReceta[idx];
    // Si el texto ya no matchea el material elegido, limpiamos selección
    if (item.materialId && item.searchQuery !== item.materialNombre) {
      item.materialId = null;
      item.materialNombre = '';
    }
    item.dropdownOpen = true;
  }

  seleccionarMaterial(idx: number, m: MaterialResponse) {
    const item = this.formReceta[idx];
    item.materialId     = m.id;
    item.materialNombre = m.nombre;
    item.searchQuery    = m.nombre;
    item.dropdownOpen   = false;
    if (!item.unidad) item.unidad = m.unidadMedida;
  }

  limpiarMaterial(idx: number) {
    const item = this.formReceta[idx];
    item.materialId     = null;
    item.materialNombre = '';
    item.searchQuery    = '';
    item.dropdownOpen   = true;
  }

  guardar() {
    if (!this.formValido) return;
    this.saving = true;

    const receta: IngredienteRecetaRequest[] = this.formReceta
      .filter(r => r.materialId != null && r.cantidad != null)
      .map(r => ({
        materialId:     r.materialId!,
        materialNombre: r.materialNombre,
        cantidad:       r.cantidad!,
        unidad:         r.unidad?.trim() || null,
        notas:          r.notas?.trim() || null,
      }));

    const request: TipoTrabajoRequest = {
      nombre:             this.form.nombre!,
      descripcion:        this.form.descripcion ?? '',
      precio:             this.form.precio ?? 0,
      categoria:          this.form.categoria!,
      tiempoEstimadoDias: this.form.tiempoEstimadoDias ?? 0,
      fotoUrl:            this.fotoPreview ?? null,
      receta,
    };

    const op$ = this.editMode && this.form.id
      ? this.catService.actualizar(this.form.id, request)
      : this.catService.crear(request);

    op$.subscribe({
      next: (res) => {
        if (this.editMode) {
          const idx = this.trabajos.findIndex(t => t.id === res.id);
          if (idx !== -1) this.trabajos[idx] = this.mapear(res);
          this.notif.exito(`"${res.nombre}" actualizado correctamente`);
        } else {
          this.trabajos.push(this.mapear(res));
          this.notif.exito(`"${res.nombre}" agregado al catálogo`);
        }
        this.filtrar();
        this.saving = false;
        this.cerrarModal();
      },
      error: (err) => {
        this.saving = false;
        this.notif.errorHttp(err, 'No se pudo guardar el trabajo');
        console.error('Error al guardar trabajo:', err);
      }
    });
  }

  // ── QUICK PRICE EDIT ───────────────────────────────────────────
  abrirEditarPrecio(trabajo: TipoTrabajo) {
    this.deleteConfirmId  = null;
    this.editandoPrecioId = trabajo.id;
    this.precioTemporal   = trabajo.precio;
  }

  guardarPrecio(trabajo: TipoTrabajo) {
    const request: TipoTrabajoRequest = {
      nombre:             trabajo.nombre,
      descripcion:        trabajo.descripcion,
      precio:             this.precioTemporal,
      categoria:          trabajo.categoria,
      tiempoEstimadoDias: trabajo.tiempoEstimadoDias,
      fotoUrl:            trabajo.foto ?? null,
    };

    this.catService.actualizar(trabajo.id, request).subscribe({
      next: (res) => {
        const idx = this.trabajos.findIndex(t => t.id === res.id);
        if (idx !== -1) this.trabajos[idx] = this.mapear(res);
        this.editandoPrecioId = null;
        this.filtrar();
        this.notif.exito(`Precio de "${res.nombre}" actualizado a ${this.formatPrecio(res.precio ?? 0)}`);
      },
      error: (err) => {
        // Actualización optimista — revertir si falla
        this.editandoPrecioId = null;
        this.notif.errorHttp(err, 'No se pudo actualizar el precio');
        console.error('Error al actualizar precio:', err);
      }
    });
  }

  cancelarPrecio() { this.editandoPrecioId = null; }

  // ── DELETE ─────────────────────────────────────────────────────
  pedirConfirmEliminar(id: number) {
    this.editandoPrecioId = null;
    this.deleteConfirmId  = id;
  }
  cancelarEliminar() { this.deleteConfirmId = null; }

  eliminar(id: number) {
    const trabajo = this.trabajos.find(t => t.id === id);
    const nombre = trabajo?.nombre ?? 'Trabajo';
    this.catService.eliminar(id).subscribe({
      next: () => {
        this.trabajos        = this.trabajos.filter(t => t.id !== id);
        this.deleteConfirmId = null;
        this.filtrar();
        this.notif.alerta(`"${nombre}" eliminado del catálogo`);
      },
      error: (err) => {
        this.deleteConfirmId = null;
        this.notif.errorHttp(err, 'No se pudo eliminar el trabajo');
        console.error('Error al eliminar trabajo:', err);
      }
    });
  }

  // ── FOTO ───────────────────────────────────────────────────────
  onFotoChange(event: Event) {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (file) this.leerFoto(file);
  }

  onDragOver(event: DragEvent)  { event.preventDefault(); this.arrastrando = true; }
  onDragLeave()                  { this.arrastrando = false; }

  onDrop(event: DragEvent) {
    event.preventDefault();
    this.arrastrando = false;
    const file = event.dataTransfer?.files?.[0];
    if (file?.type.startsWith('image/')) this.leerFoto(file);
  }

  private leerFoto(file: File) {
    const reader = new FileReader();
    reader.onload = e => {
      this.fotoPreview = e.target?.result as string;
      this.form.foto   = this.fotoPreview;
    };
    reader.readAsDataURL(file);
  }

  quitarFoto() {
    this.fotoPreview = undefined;
    this.form.foto   = undefined;
  }

  // ── HELPERS ────────────────────────────────────────────────────
  categoriaLabel(cat: Categoria): string {
    return this.categoriasForm.find(c => c.valor === cat)?.label ?? cat;
  }

  formatPrecio(precio: number): string {
    if (!precio) return 'A convenir';
    return new Intl.NumberFormat('es-AR', {
      style: 'currency', currency: 'ARS', maximumFractionDigits: 0
    }).format(precio);
  }

  get modalTitle(): string {
    return this.editMode ? 'Editar trabajo' : 'Nuevo trabajo';
  }
}
