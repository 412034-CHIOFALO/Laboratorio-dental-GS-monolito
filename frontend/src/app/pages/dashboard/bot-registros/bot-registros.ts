import { Component, OnInit, OnDestroy, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription, interval } from 'rxjs';
import { startWith, switchMap, catchError } from 'rxjs/operators';
import { of } from 'rxjs';
import {
  SueldosService, RegistroBot, EstadoRegistroBot, TipoReceptorBot,
} from '../../../services/sueldos.service';
import { BotEstadoService, EstadoBot } from '../../../services/bot-estado.service';
import { NotificationService } from '../../../services/notification.service';
import { TableSort } from '../../../shared/table-sort';

/**
 * Historial del bot de WhatsApp + estado de conexión en vivo.
 * Muestra un badge de conexión arriba; si el bot se desvincula, aparece
 * el panel con el QR para re-vincularlo sin salir de esta página.
 */
@Component({
  selector: 'app-bot-registros',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './bot-registros.html',
  styleUrl: './bot-registros.css',
})
export class BotRegistrosComponent implements OnInit, OnDestroy {
  private sueldos = inject(SueldosService);
  private botEstado = inject(BotEstadoService);
  private notif = inject(NotificationService);

  // ── Historial ──────────────────────────────────────────────────────────────
  registros = signal<RegistroBot[]>([]);
  cargando = signal(false);
  filtroEstado = signal<'TODOS' | EstadoRegistroBot>('TODOS');

  // ── Estado del bot ─────────────────────────────────────────────────────────
  estado = signal<EstadoBot | null>(null);
  sinConexionBot = signal(false);
  modalAbierto = signal(false);

  private pollSub?: Subscription;
  private pollRegistrosSub?: Subscription;

  ngOnInit(): void {
    this.cargar();
    this.pollSub = interval(3000).pipe(
      startWith(0),
      switchMap(() => this.botEstado.estado().pipe(catchError(() => of(null)))),
    ).subscribe(e => {
      if (e) { this.estado.set(e); this.sinConexionBot.set(false); }
      else    { this.sinConexionBot.set(true); }
    });
    // Refresco silencioso del historial: con dos pestañas abiertas, o mientras
    // llegan comprobantes nuevos por WhatsApp, sin esto quedaba desactualizado.
    this.pollRegistrosSub = interval(6000).subscribe(() => this.cargar(true));
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
    this.pollRegistrosSub?.unsubscribe();
  }

  // ── Historial ──────────────────────────────────────────────────────────────
  cargar(silencioso = false): void {
    if (!silencioso) this.cargando.set(true);
    this.sueldos.registrosBot().subscribe({
      next: (r) => { this.registros.set(r); if (!silencioso) this.cargando.set(false); },
      error: (e) => { if (!silencioso) { this.notif.errorHttp(e, 'No se pudo cargar el historial del bot'); this.cargando.set(false); } },
    });
  }

  setFiltro(f: 'TODOS' | EstadoRegistroBot): void { this.filtroEstado.set(f); }

  readonly sort = new TableSort<RegistroBot>('fechaHora', 'desc');

  filtrados(): RegistroBot[] {
    const f = this.filtroEstado();
    const base = f === 'TODOS' ? this.registros() : this.registros().filter(r => r.estado === f);
    return this.sort.aplicar(base);
  }

  contar(estado: EstadoRegistroBot): number {
    return this.registros().filter(r => r.estado === estado).length;
  }

  verComprobante(r: RegistroBot): void {
    if (!r.tieneComprobante) return;
    this.sueldos.descargarComprobanteRegistro(r.id).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        window.open(url, '_blank');
        setTimeout(() => URL.revokeObjectURL(url), 15000);
      },
      error: (e) => this.notif.errorHttp(e, 'No se pudo abrir el comprobante'),
    });
  }

  claseEstado(e: EstadoRegistroBot): string {
    return ({ REGISTRADO: 'badge-ok', RECHAZADO: 'badge-err', DUPLICADO: 'badge-dup', PENDIENTE: 'badge-pend' } as const)[e] ?? '';
  }
  claseTipo(t: TipoReceptorBot | null): string {
    return ({ EMPLEADO: 'tipo-emp', PROVEEDOR: 'tipo-prov', DESCONOCIDO: 'tipo-desc' } as const)[t ?? 'DESCONOCIDO'] ?? '';
  }
  labelTipo(t: TipoReceptorBot | null): string {
    return ({ EMPLEADO: 'Sueldo', PROVEEDOR: 'Proveedor', DESCONOCIDO: '—' } as const)[t ?? 'DESCONOCIDO'] ?? '—';
  }

  // ── Estado del bot ─────────────────────────────────────────────────────────
  abrirModal(): void  { this.modalAbierto.set(true); }
  cerrarModal(): void { this.modalAbierto.set(false); }

  regenerandoQr = signal(false);

  regenerarQr(): void {
    if (this.regenerandoQr()) return;
    this.regenerandoQr.set(true);
    this.botEstado.regenerarQr().subscribe({
      next: () => {
        this.notif.exito('QR en regeneración — puede tardar unos segundos.');
        this.regenerandoQr.set(false);
      },
      error: (e) => {
        this.notif.errorHttp(e, 'No se pudo regenerar el QR');
        this.regenerandoQr.set(false);
      },
    });
  }

  // ── Revisar mensajes perdidos (reconciliación) ────────────────────────────
  reconciliando = signal(false);

  reconciliar(): void {
    if (this.reconciliando()) return;
    this.reconciliando.set(true);
    this.botEstado.reconciliar().subscribe({
      next: res => {
        this.notif.exito(res.mensaje || 'El bot está revisando el historial reciente.', 'Revisión iniciada');
        this.reconciliando.set(false);
        // El bot procesa en segundo plano (OCR/IA por cada grupo) — refrescamos
        // el historial un rato después para que se vea lo que fue encontrando.
        setTimeout(() => this.cargar(), 8000);
      },
      error: (e) => {
        this.notif.errorHttp(e, 'No se pudo iniciar la revisión');
        this.reconciliando.set(false);
      },
    });
  }

  claseConexion(): 'ok' | 'warn' | 'err' {
    if (this.sinConexionBot()) return 'err';
    const e = this.estado();
    if (!e) return 'warn';
    return e.conectado ? 'ok' : 'warn';
  }

  labelConexion(): string {
    if (this.sinConexionBot()) return 'Sin conexión con el bot';
    const e = this.estado();
    if (!e) return 'Consultando…';
    return e.conectado ? 'Conectado' : 'Desvinculado — escaneá el QR';
  }

  mostrarQr(): boolean {
    const e = this.estado();
    return !this.sinConexionBot() && !!e && !e.conectado && !!e.qrDataUrl;
  }
}
