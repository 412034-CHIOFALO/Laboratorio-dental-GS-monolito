import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ThemeService } from '../../services/theme.service';

interface ItemGaleria {
  tipo: 'foto' | 'video';
  /** Nombre del archivo dentro de public/instagram/. */
  archivo: string;
  alt: string;
  caption: string;
}

/**
 * Galería de "Nuestros trabajos" (sección Instagram de la landing).
 * Los archivos van en frontend-app/public/instagram/ — ver el README ahí
 * mismo para el detalle de nombres y formatos esperados.
 */
const GALERIA_INSTAGRAM: ItemGaleria[] = [
  { tipo: 'video', archivo: 'reel-2.mp4', alt: 'Trabajo del laboratorio GS Ortodoncia', caption: 'Precisión en cada detalle' },
];

@Component({
  selector: 'app-landing-page',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './landing-page.html',
  styleUrls: ['./landing-page.css'],
})
export class LandingPage {
  readonly galeria = GALERIA_INSTAGRAM;
  readonly themeService = inject(ThemeService);
  mobileMenuOpen = false;

  toggleMenu() {
    this.mobileMenuOpen = !this.mobileMenuOpen;
  }

  closeMenu() {
    this.mobileMenuOpen = false;
  }
}
