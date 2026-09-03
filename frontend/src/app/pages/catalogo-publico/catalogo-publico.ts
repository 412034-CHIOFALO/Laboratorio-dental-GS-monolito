import { Component, OnInit, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { CatalogoService, Categoria, TipoTrabajoPublicoResponse } from '../../services/catalogo.service';
import { ThemeService } from '../../services/theme.service';

const CATEGORIA_LABEL: Record<Categoria, string> = {
  FIJA: 'Prótesis Fija',
  REMOVIBLE: 'Removible',
  ORTODONCIA: 'Ortodoncia',
  ATM: 'ATM',
  PERSONALIZADO: 'Personalizado',
};

/**
 * Catálogo público (sin login) — precios sí, receta de materiales no (esa
 * distinción la hace el backend, ver TipoTrabajoPublicoResponse). El acceso
 * a esta ruta ya lo filtra catalogoPublicoGuard antes de llegar acá; el
 * manejo de error de abajo es solo una red de seguridad ante una condición
 * de carrera (el ADMIN apaga el toggle justo entre el guard y este fetch).
 */
@Component({
  selector: 'app-catalogo-publico',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './catalogo-publico.html',
  styleUrls: ['./catalogo-publico.css'],
})
export class CatalogoPublicoComponent implements OnInit {
  private readonly catalogo = inject(CatalogoService);
  private readonly router = inject(Router);
  readonly themeService = inject(ThemeService);

  readonly trabajos = signal<TipoTrabajoPublicoResponse[]>([]);
  readonly cargando = signal(true);

  ngOnInit(): void {
    this.catalogo.listarPublico().subscribe({
      next: trabajos => {
        this.trabajos.set(trabajos);
        this.cargando.set(false);
      },
      error: () => this.router.navigate(['/catalogo-no-disponible']),
    });
  }

  categoriaLabel(cat: Categoria): string {
    return CATEGORIA_LABEL[cat] ?? cat;
  }

  formatPrecio(precio: number): string {
    if (!precio) return 'A consultar';
    return new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 0 }).format(precio);
  }
}
