import { Component, OnInit, inject, signal, DestroyRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TableSort } from '../../../shared/table-sort';
import {
  ProveedoresService, Proveedor, DeudaProveedor, ProveedorRequest, DeudaProveedorRequest, CajaPagoProveedor,
} from '../../../services/proveedores.service';
import { NotificationService } from '../../../services/notification.service';
import { iniciarPolling } from '../../../shared/poll.util';

/**
 * Pantalla de Proveedores: lista de proveedores con su deuda pendiente y, al
 * expandir, el detalle de sus deudas (incluidas las saldadas por el bot vía
 * pagos directos o triangulados). Permite dar de alta proveedores.
 */
@Component({
  selector: 'app-proveedores',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './proveedores.html',
  styleUrl: './proveedores.css',
})
export class ProveedoresComponent implements OnInit {
  private prov = inject(ProveedoresService);
  private notif = inject(NotificationService);
  private destroyRef = inject(DestroyRef);

  proveedores = signal<Proveedor[]>([]);
  cargando = signal(false);

  readonly sort = new TableSort<Proveedor>();
  /** Proveedores ordenados para la tabla. */
  proveedoresVista(): Proveedor[] {
    return this.sort.aplicar(this.proveedores());
  }

  expandido = signal<number | null>(null);
  deudas = signal<DeudaProveedor[]>([]);
  cargandoDeudas = signal(false);

  modalAbierto = signal(false);
  guardando = signal(false);
  form: ProveedorRequest = this.formVacio();

  // ── Modal de nueva deuda ────────────────────────────────────────────
  modalDeudaAbierto = signal(false);
  guardandoDeuda    = signal(false);
  pagandoId         = signal<number | null>(null);
  proveedorDeuda: Proveedor | null = null;
  deudaForm: DeudaProveedorRequest = this.deudaFormVacio();

  // ── Modal "¿de qué caja salió el pago?" (al marcar una deuda como pagada) ──
  modalPagoAbierto = signal(false);
  deudaAPagar: DeudaProveedor | null = null;

  ngOnInit(): void {
    this.cargar();
    iniciarPolling(() => this.cargar(true), this.destroyRef);
  }

  cargar(silencioso = false): void {
    if (silencioso && (this.modalAbierto() || this.modalDeudaAbierto())) return;
    if (!silencioso) this.cargando.set(true);
    this.prov.listar().subscribe({
      next: (p) => { this.proveedores.set(p); if (!silencioso) this.cargando.set(false); },
      error: (e) => {
        if (!silencioso) {
          this.notif.errorHttp(e, 'No se pudieron cargar los proveedores');
          this.cargando.set(false);
        }
      },
    });
  }

  totalProveedores(): number { return this.proveedores().length; }
  totalDeuda(): number { return this.proveedores().reduce((s, p) => s + (p.deudaPendiente || 0), 0); }

  /** Mismo formato que el resto del panel (es-AR, sin decimales) — antes esta pantalla usaba el pipe "number" a secas, que cae al locale por defecto (en-US, coma de miles). */
  formatMoney(n: number | null | undefined): string {
    return new Intl.NumberFormat('es-AR', {
      style: 'currency', currency: 'ARS', maximumFractionDigits: 0
    }).format(n ?? 0);
  }

  toggle(p: Proveedor): void {
    if (this.expandido() === p.id) { this.expandido.set(null); return; }
    this.expandido.set(p.id);
    this.deudas.set([]);
    this.cargandoDeudas.set(true);
    this.prov.deudas(p.id).subscribe({
      next: (d) => { this.deudas.set(d); this.cargandoDeudas.set(false); },
      error: (e) => { this.notif.errorHttp(e, 'No se pudieron cargar las deudas'); this.cargandoDeudas.set(false); },
    });
  }

  abrirModal(): void { this.form = this.formVacio(); this.modalAbierto.set(true); }
  cerrarModal(): void { this.modalAbierto.set(false); }

  /** Deja solo dígitos y guiones en el CUIT mientras se tipea. */
  sanitizarCuit(v: string): string { return (v || '').replace(/[^0-9-]/g, '').slice(0, 13); }

  /** Deja solo números y símbolos de teléfono (+ - ( ) espacio). */
  sanitizarTelefono(v: string): string { return (v || '').replace(/[^0-9+()\-\s]/g, '').slice(0, 20); }

  guardar(): void {
    if (!this.form.nombre?.trim()) {
      this.notif.alerta('El nombre es obligatorio.', 'Falta el nombre');
      return;
    }
    // Validaciones de formato (además de la que hace el backend).
    const cuit = this.form.cuit?.trim();
    if (cuit && !/^[0-9]{2}-?[0-9]{8}-?[0-9]{1}$/.test(cuit)) {
      this.notif.alerta('El CUIT debe tener 11 dígitos (ej: 30-12345678-9).', 'CUIT inválido');
      return;
    }
    const tel = this.form.telefono?.trim();
    if (tel && !/^[0-9+()\-\s]{6,20}$/.test(tel)) {
      this.notif.alerta('El teléfono solo puede tener números y los símbolos + - ( ).', 'Teléfono inválido');
      return;
    }
    this.guardando.set(true);
    this.prov.crear(this.form).subscribe({
      next: () => {
        this.notif.exito('Proveedor creado correctamente.', 'Listo');
        this.guardando.set(false);
        this.modalAbierto.set(false);
        this.cargar();
      },
      error: (e) => { this.notif.errorHttp(e, 'No se pudo crear el proveedor'); this.guardando.set(false); },
    });
  }

  private formVacio(): ProveedorRequest {
    return { nombre: '', cuit: '', email: '', telefono: '', direccion: '' };
  }

  // ── Nueva deuda ──────────────────────────────────────────────────────

  abrirModalDeuda(p: Proveedor): void {
    this.proveedorDeuda = p;
    this.deudaForm = this.deudaFormVacio();
    this.deudaForm.proveedorId = p.id;
    this.modalDeudaAbierto.set(true);
  }

  cerrarModalDeuda(): void { this.modalDeudaAbierto.set(false); }

  guardarDeuda(): void {
    if (!this.deudaForm.descripcion?.trim()) {
      this.notif.alerta('La descripción es obligatoria.', 'Falta la descripción');
      return;
    }
    if (!this.deudaForm.monto || this.deudaForm.monto <= 0) {
      this.notif.alerta('El monto debe ser mayor a cero.', 'Monto inválido');
      return;
    }
    this.guardandoDeuda.set(true);
    this.prov.registrarDeuda(this.deudaForm).subscribe({
      next: () => {
        this.notif.exito('Deuda registrada correctamente.', 'Listo');
        this.guardandoDeuda.set(false);
        this.modalDeudaAbierto.set(false);
        this.cargar();
        if (this.proveedorDeuda) this.refrescarDeudas(this.proveedorDeuda.id);
      },
      error: (e) => { this.notif.errorHttp(e, 'No se pudo registrar la deuda'); this.guardandoDeuda.set(false); },
    });
  }

  /** Abre el modal para elegir de qué caja salió el pago antes de marcarla como pagada. */
  marcarPagada(d: DeudaProveedor): void {
    this.deudaAPagar = d;
    this.modalPagoAbierto.set(true);
  }

  cerrarModalPago(): void {
    this.modalPagoAbierto.set(false);
    this.deudaAPagar = null;
  }

  confirmarPago(caja: CajaPagoProveedor): void {
    const d = this.deudaAPagar;
    if (!d) return;
    this.pagandoId.set(d.id);
    this.prov.pagarDeuda(d.id, caja).subscribe({
      next: () => {
        this.notif.exito('Deuda marcada como pagada.', 'Listo');
        this.pagandoId.set(null);
        this.cerrarModalPago();
        this.cargar();
        this.refrescarDeudas(d.proveedorId);
      },
      error: (e) => { this.notif.errorHttp(e, 'No se pudo marcar la deuda como pagada'); this.pagandoId.set(null); },
    });
  }

  private refrescarDeudas(proveedorId: number): void {
    this.prov.deudas(proveedorId).subscribe({ next: (d) => this.deudas.set(d) });
  }

  private deudaFormVacio(): DeudaProveedorRequest {
    return { proveedorId: 0, descripcion: '', monto: 0, fechaVencimiento: null, nroFacturaProveedor: '', observaciones: '' };
  }
}
