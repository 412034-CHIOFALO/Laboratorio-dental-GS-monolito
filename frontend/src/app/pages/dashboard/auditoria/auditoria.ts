import { Component, OnInit, inject, DestroyRef } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { AuthService } from '../../../services/auth';
import { NotificationService } from '../../../services/notification.service';
import { environment } from '../../../../environments/environment';
import { clonar, MOCK_AUDIT, MockAuditEvent, TipoAudit } from '../../../services/mock-data';
import { iniciarPolling } from '../../../shared/poll.util';
import { TableSort } from '../../../shared/table-sort';

@Component({
  selector: 'app-auditoria',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './auditoria.html',
  styleUrls: ['./auditoria.css'],
})
export class AuditoriaComponent implements OnInit {
  private gatewayUrl = environment.gatewayUrl;

  events: MockAuditEvent[] = [];
  filtros: MockAuditEvent[] = [];

  readonly sort = new TableSort<MockAuditEvent>('timestamp', 'desc');
  /** Lista filtrada Y ordenada para la tabla. */
  get filtrosVista(): MockAuditEvent[] {
    return this.sort.aplicar(this.filtros);
  }
  busqueda   = '';
  tipoFiltro: TipoAudit | '' = '';
  loading    = false;
  error      = '';

  readonly tiposAudit: { valor: TipoAudit | ''; label: string }[] = [
    { valor: '',          label: 'Todos los eventos'    },
    { valor: 'LOGIN',     label: 'Inicio de sesión'     },
    { valor: 'CREAR',     label: 'Crear'                },
    { valor: 'EDITAR',    label: 'Editar'               },
    { valor: 'ELIMINAR',  label: 'Eliminar'             },
    { valor: 'PAGO',      label: 'Pagos'                },
    { valor: 'COBRO',     label: 'Cobros'               },
    { valor: 'SUELDO',    label: 'Sueldos'              },
    { valor: 'PROVEEDOR', label: 'Pagos a proveedores'  },
    { valor: 'CAJA',      label: 'Movimientos de caja'  },
    { valor: 'STOCK',     label: 'Movimientos de stock' },
    { valor: 'ESTADO',    label: 'Cambio de estado'     },
    { valor: 'ENTREGA',   label: 'Entregas'             },
    { valor: 'BACKUP',    label: 'Backups'              },
  ];

  private destroyRef = inject(DestroyRef);

  private notif = inject(NotificationService);
  backupCorriendo = false;

  constructor(private http: HttpClient, private authService: AuthService) {}

  /** Dispara el backup a demanda (mismo backup que corre a las 3 AM). */
  hacerBackup(): void {
    if (this.backupCorriendo) return;
    if (environment.useMocks) {
      this.notif.info('En modo demo el backup no se ejecuta (no hay backend).');
      return;
    }
    this.backupCorriendo = true;
    this.http.post(`${this.gatewayUrl}/api/auth/backup/run`, {}, { headers: this.headers() }).subscribe({
      next: () => {
        this.backupCorriendo = false;
        this.notif.exito('Backup iniciado. Puede tardar unos minutos; el resultado queda en el log del backup.', 'Backup en curso');
        // El backup registra su propio evento en la bitácora al terminar — refrescamos.
        setTimeout(() => this.cargar(true), 3000);
      },
      error: (e) => {
        this.backupCorriendo = false;
        if (e.status === 409) this.notif.alerta('Ya hay un backup en curso. Esperá a que termine.');
        else this.notif.errorHttp(e, 'No se pudo iniciar el backup');
      },
    });
  }

  ngOnInit(): void {
    this.cargar();
    iniciarPolling(() => this.cargar(true), this.destroyRef);
  }

  private headers(): HttpHeaders {
    return new HttpHeaders({ Authorization: `Bearer ${this.authService.getToken()}` });
  }

  cargar(silencioso = false): void {
    if (!silencioso) { this.loading = true; this.error = ''; }

    if (environment.useMocks) {
      setTimeout(() => {
        this.events  = clonar(MOCK_AUDIT).sort((a: MockAuditEvent, b: MockAuditEvent) =>
          new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime());
        this.filtrar();
        if (!silencioso) this.loading = false;
      }, 200);
      return;
    }

    this.http.get<MockAuditEvent[]>(`${this.gatewayUrl}/api/auth/auditoria`, { headers: this.headers() })
      .subscribe({
        next: (data) => {
          this.events  = data;
          this.filtrar();
          if (!silencioso) this.loading = false;
        },
        error: () => {
          if (!silencioso) {
            this.error   = 'No se pudo cargar el registro de auditoría.';
            this.loading = false;
          }
        }
      });
  }

  filtrar(): void {
    this.filtros = this.events.filter(e => {
      const matchTipo = !this.tipoFiltro || e.tipo === this.tipoFiltro;
      const q         = this.busqueda.toLowerCase();
      const matchText = !q || [e.usuario, e.accion, e.entidad, e.detalle]
        .some(s => s?.toLowerCase().includes(q));
      return matchTipo && matchText;
    });
  }

  formatTs(iso: string): string {
    const d   = new Date(iso);
    const hoy = new Date();
    const esHoy = d.toDateString() === hoy.toDateString();
    if (esHoy) return d.toLocaleTimeString('es-AR', { hour: '2-digit', minute: '2-digit' });
    return d.toLocaleDateString('es-AR', { day: '2-digit', month: '2-digit' }) + ' ' +
           d.toLocaleTimeString('es-AR', { hour: '2-digit', minute: '2-digit' });
  }

  colorTipo(tipo: TipoAudit): string {
    const m: Record<TipoAudit, string> = {
      LOGIN: 'cyan', CREAR: 'green', EDITAR: 'blue',
      ELIMINAR: 'rose', PAGO: 'green', ESTADO: 'purple', BACKUP: 'neutral',
      // Ingresos a la cuenta corriente del odontólogo (cobro) — misma familia que PAGO.
      COBRO: 'green',
      // Egresos: sueldos y pagos a proveedores, directos o triangulados.
      SUELDO: 'amber', PROVEEDOR: 'amber',
      // Ajustes/movimientos de un recurso (caja o stock) — misma familia que EDITAR.
      CAJA: 'blue', STOCK: 'blue',
      // Entrega de un pedido — parte del mismo ciclo de vida que ESTADO.
      ENTREGA: 'purple',
    };
    return m[tipo] ?? 'neutral';
  }

  iconTipo(tipo: TipoAudit): string {
    const m: Record<TipoAudit, string> = {
      LOGIN:    'M11 16l-4-4m0 0l4-4m-4 4h14m-5 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h7a3 3 0 013 3v1',
      CREAR:    'M12 4v16m8-8H4',
      EDITAR:   'M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z',
      ELIMINAR: 'M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16',
      PAGO:     'M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z',
      ESTADO:   'M7 16V4m0 0L3 8m4-4l4 4m6 0v12m0 0l4-4m-4 4l-4-4',
      BACKUP:   'M4 4h16v6H4V4zm0 10h16v6H4v-6zm4-6h.01M8 18h.01',
      // Cobro/pago/sueldo/proveedor comparten el ícono de dinero — la columna
      // ENTIDAD ya distingue de qué se trata cada evento puntual.
      COBRO:     'M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z',
      SUELDO:    'M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z',
      PROVEEDOR: 'M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z',
      CAJA:      'M20 12V7H4v5m16 0v7a1 1 0 01-1 1H5a1 1 0 01-1-1v-7m16 0H4',
      STOCK:     'M21 8l-9-5-9 5 9 5 9-5zM3 8v8l9 5 9-5V8M12 13v8',
      ENTREGA:   'M7 16V4m0 0L3 8m4-4l4 4m6 0v12m0 0l4-4m-4 4l-4-4',
    };
    return m[tipo] ?? '';
  }
}
