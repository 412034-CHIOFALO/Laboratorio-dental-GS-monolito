import { Injectable, signal } from '@angular/core';

export type Theme = 'dark' | 'light';

const STORAGE_KEY = 'gs-theme';
const DEFAULT_THEME: Theme = 'dark';

/**
 * Gestiona el tema visual (claro/oscuro) de la app.
 *
 * - Lee la preferencia desde localStorage al iniciar
 * - Aplica el atributo `data-theme` al <html> para que los selectores CSS
 *   `[data-theme="light"]` definidos en styles.css tomen efecto
 * - Persiste el cambio en localStorage
 *
 * El default es DARK (continuidad con todas las pantallas ya construidas).
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {

  /** Signal reactivo con el tema actual. Los componentes lo leen para mostrar el icono apropiado. */
  readonly current = signal<Theme>(DEFAULT_THEME);

  constructor() {
    const stored = this.leerDeStorage();
    this.aplicar(stored);
  }

  toggle(): void {
    this.aplicar(this.current() === 'dark' ? 'light' : 'dark');
  }

  set(theme: Theme): void {
    this.aplicar(theme);
  }

  private aplicar(theme: Theme): void {
    this.current.set(theme);
    document.documentElement.setAttribute('data-theme', theme);
    try {
      localStorage.setItem(STORAGE_KEY, theme);
    } catch {
      // Storage no disponible (modo privado), igual aplica el tema en runtime
    }
  }

  private leerDeStorage(): Theme {
    try {
      const v = localStorage.getItem(STORAGE_KEY);
      if (v === 'light' || v === 'dark') return v;
    } catch { /* noop */ }
    return DEFAULT_THEME;
  }
}
