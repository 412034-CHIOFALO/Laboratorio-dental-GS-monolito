import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-privacidad',
  imports: [RouterLink],
  templateUrl: './privacidad.html',
  styleUrls: ['./privacidad.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PrivacidadComponent {}
