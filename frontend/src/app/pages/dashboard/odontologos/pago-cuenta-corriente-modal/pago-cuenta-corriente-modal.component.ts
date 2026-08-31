import { Component, EventEmitter, Input, OnInit, Output, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { FinanzasService, MedioPago, PagoCuentaCorrienteResponse } from '../../../../services/finanzas.service';
import { SueldosService, PagoTrianguladoProveedorResponse } from '../../../../services/sueldos.service';
import { ProveedoresService, Proveedor } from '../../../../services/proveedores.service';
import { NotificationService } from '../../../../services/notification.service';
import { hoyComoLocalDate } from '../../../../services/date-utils';

export type ModoPago = 'DIRECTO' | 'PROVEEDOR';

/**
 * Modal de "Registrar pago a cuenta corriente" reutilizable: lo usan tanto la lista
 * de Odontólogos como el ranking de morosos en Finanzas y la ficha de historial,
 * para no duplicar la lógica de imputación (deudas más viejas primero) en cada lugar.
 *
 * También permite el modo PROVEEDOR: el odontólogo pagó directo a un proveedor
 * por el laboratorio, saldando su cuenta corriente y la deuda del proveedor
 * al mismo tiempo (carga manual de lo que el bot ya hace automático).
 */
@Component({
  selector: 'app-pago-cuenta-corriente-modal',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './pago-cuenta-corriente-modal.component.html',
  styleUrls: ['./pago-cuenta-corriente-modal.component.css'],
})
export class PagoCuentaCorrienteModalComponent implements OnInit {
  @Input({ required: true }) odontologoId!: number;
  @Input({ required: true }) odontologoNombre!: string;
  @Input() saldoActual = 0;
  @Output() cerrar = new EventEmitter<void>();
  @Output() pagado = new EventEmitter<PagoCuentaCorrienteResponse | PagoTrianguladoProveedorResponse>();

  private finanzas = inject(FinanzasService);
  private sueldos = inject(SueldosService);
  private proveedoresService = inject(ProveedoresService);
  private notif = inject(NotificationService);

  saving = signal(false);
  proveedores = signal<Proveedor[]>([]);
  modo: ModoPago = 'DIRECTO';

  form = {
    monto: null as number | null,
    medio: 'TRANSFERENCIA' as MedioPago,
    fecha: hoyComoLocalDate(),
    nota: '',
  };

  formProveedor = {
    proveedorId: null as number | null,
    monto: null as number | null,
    nota: '',
  };

  ngOnInit(): void {
    this.proveedoresService.listar().subscribe(lista => this.proveedores.set(lista));
  }

  get valido(): boolean {
    if (this.modo === 'PROVEEDOR') {
      return this.formProveedor.proveedorId != null && this.formProveedor.monto != null && this.formProveedor.monto > 0;
    }
    return this.form.monto != null && this.form.monto > 0;
  }

  confirmar(): void {
    if (!this.valido || this.saving()) return;
    this.saving.set(true);
    if (this.modo === 'PROVEEDOR') {
      this.sueldos.registrarPagoTrianguladoProveedor(this.odontologoId, {
        proveedorId: this.formProveedor.proveedorId!,
        monto: this.formProveedor.monto!,
        nota: this.formProveedor.nota.trim() || null,
      }).subscribe({
        next: res => {
          this.saving.set(false);
          this.notif.exito(res.mensaje);
          this.pagado.emit(res);
        },
        error: err => {
          this.saving.set(false);
          this.notif.errorHttp(err, 'No se pudo registrar el pago al proveedor');
        },
      });
      return;
    }
    this.finanzas.registrarPagoCuentaCorriente(this.odontologoId, {
      monto: this.form.monto!,
      medio: this.form.medio,
      fecha: this.form.fecha,
      nota: this.form.nota.trim() || null,
    }).subscribe({
      next: res => {
        this.saving.set(false);
        this.notif.exito(res.mensaje);
        this.pagado.emit(res);
      },
      error: err => {
        this.saving.set(false);
        this.notif.errorHttp(err, 'No se pudo registrar el pago');
      },
    });
  }

  formatPrecio(n: number): string {
    return new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 0 }).format(n);
  }
}
