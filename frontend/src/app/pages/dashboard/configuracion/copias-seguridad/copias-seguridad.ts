import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { BackupService, CopiaSeguridad, DestinoCopia, EstadoBackup } from '../../../../services/backup.service';
import { NotificationService } from '../../../../services/notification.service';

const SEGUIMIENTO_MS = 4000;

const ETIQUETA_DESTINO: Record<DestinoCopia, { texto: string; clase: string; ayuda: string }> = {
  local: { texto: 'Solo en el servidor', clase: 'neutro', ayuda: 'La copia está en el disco del servidor; no se subió a Drive.' },
  drive: { texto: 'Servidor + Drive', clase: 'ok', ayuda: 'La copia está en el servidor y también en Google Drive.' },
  'drive-fallo': { texto: 'Falló Drive', clase: 'aviso', ayuda: 'La copia está en el servidor, pero la subida a Google Drive falló.' },
  desconocido: { texto: 'Sin dato de Drive', clase: 'neutro', ayuda: 'Copia hecha antes de que el sistema registrara a dónde llegaba.' },
};

/**
 * Configuración → Copias de seguridad (solo ADMIN): el botón para hacer el backup ahora, si Google Drive está conectado y
 * la lista de copias del servidor, cada una con su botón para bajarla a la computadora.
 */
@Component({
  selector: 'app-copias-seguridad',
  templateUrl: './copias-seguridad.html',
  styleUrls: ['./copias-seguridad.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CopiasSeguridadComponent implements OnInit {
  private readonly backup = inject(BackupService);
  private readonly notif = inject(NotificationService);

  readonly estado = signal<EstadoBackup | null>(null);
  readonly cargando = signal(true);
  readonly fallo = signal(false);
  readonly iniciando = signal(false);
  readonly bajando = signal<string | null>(null);

  readonly copias = computed(() => this.estado()?.copias ?? []);
  readonly ocupado = computed(() => this.iniciando() || (this.estado()?.enCurso ?? false));

  private seguimiento?: ReturnType<typeof setInterval>;
  private ultimaAntes?: string;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.dejarDeSeguir());
  }

  ngOnInit(): void {
    this.cargar();
  }

  cargar(silencioso = false): void {
    if (!silencioso) {
      this.cargando.set(true);
      this.fallo.set(false);
    }
    this.backup.estado().subscribe({
      next: estado => {
        const estabaEnCurso = this.estado()?.enCurso ?? false;
        this.estado.set(estado);
        this.cargando.set(false);
        this.fallo.set(false);
        if (estado.enCurso) this.seguir();
        else if (estabaEnCurso || this.seguimiento) this.terminoElBackup(estado);
      },
      error: () => {
        this.cargando.set(false);
        if (!silencioso) this.fallo.set(true);
      },
    });
  }

  hacerBackup(): void {
    if (this.ocupado()) return;
    this.ultimaAntes = this.copias()[0]?.nombre;
    this.iniciando.set(true);
    this.backup.ejecutar().subscribe({
      next: () => {
        this.iniciando.set(false);
        this.estado.update(e => (e ? { ...e, enCurso: true } : e));
        this.seguir();
      },
      error: (e: HttpErrorResponse) => {
        this.iniciando.set(false);
        if (e.status === 409) {
          this.notif.alerta('Ya hay un backup en curso. Esperá a que termine.');
          this.cargar(true);
        } else {
          this.notif.errorHttp(e, 'No se pudo iniciar el backup');
        }
      },
    });
  }

  descargar(copia: CopiaSeguridad): void {
    if (this.bajando()) return;
    this.bajando.set(copia.nombre);
    this.backup.descargar(copia.nombre).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        const enlace = document.createElement('a');
        enlace.href = url;
        enlace.download = `gs-backup-${copia.nombre}.tar`;
        enlace.click();
        setTimeout(() => URL.revokeObjectURL(url), 15_000);
        this.bajando.set(null);
        this.notif.exito('Guardala en un lugar seguro: tiene todos los datos del laboratorio.', 'Copia descargada');
      },
      error: (e: HttpErrorResponse) => {
        this.bajando.set(null);
        this.avisarFalloDeDescarga(e);
      },
    });
  }

  fecha(nombre: string): string {
    const m = /^(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})$/.exec(nombre);
    return m ? `${m[3]}/${m[2]}/${m[1]} ${m[4]}:${m[5]}` : nombre;
  }

  tamanio(bytes: number): string {
    const unidades = ['B', 'KB', 'MB', 'GB'];
    let valor = bytes;
    let i = 0;
    while (valor >= 1024 && i < unidades.length - 1) {
      valor /= 1024;
      i++;
    }
    const texto = valor.toLocaleString('es-AR', { maximumFractionDigits: i === 0 ? 0 : 1 });
    return `${texto} ${unidades[i]}`;
  }

  etiqueta(destino: DestinoCopia) {
    return ETIQUETA_DESTINO[destino] ?? ETIQUETA_DESTINO.desconocido;
  }

  private seguir(): void {
    if (this.seguimiento) return;
    this.ultimaAntes ??= this.copias()[0]?.nombre;
    this.seguimiento = setInterval(() => this.cargar(true), SEGUIMIENTO_MS);
  }

  private dejarDeSeguir(): void {
    if (this.seguimiento) clearInterval(this.seguimiento);
    this.seguimiento = undefined;
  }

  private terminoElBackup(estado: EstadoBackup): void {
    this.dejarDeSeguir();
    const nueva = estado.copias[0];
    if (!nueva || nueva.nombre === this.ultimaAntes) {
      this.notif.alerta('El backup terminó pero no dejó una copia nueva. Mirá la bitácora de Auditoría.', 'Backup con problemas');
    } else if (nueva.destino === 'drive-fallo') {
      this.notif.alerta('La copia quedó en el servidor, pero no se pudo subir a Google Drive.', 'Backup hecho, Drive falló');
    } else {
      this.notif.exito(`Copia del ${this.fecha(nueva.nombre)} (${this.tamanio(nueva.bytes)}).`, 'Backup terminado');
    }
    this.ultimaAntes = undefined;
  }

  /** Con `responseType: 'blob'` el mensaje del servidor llega dentro de un Blob: se lee para no mostrar un error genérico. */
  private async avisarFalloDeDescarga(e: HttpErrorResponse): Promise<void> {
    let mensaje: string | undefined;
    if (e.error instanceof Blob) {
      try {
        mensaje = (JSON.parse(await e.error.text()) as { error?: string }).error;
      } catch {
        mensaje = undefined;
      }
    }
    if (mensaje) this.notif.error(mensaje, 'No se pudo descargar la copia');
    else this.notif.errorHttp(e, 'No se pudo descargar la copia');
  }
}
