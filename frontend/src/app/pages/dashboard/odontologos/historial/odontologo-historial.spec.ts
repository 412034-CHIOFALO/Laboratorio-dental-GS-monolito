import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { OdontologoHistorialComponent } from './odontologo-historial';
import { OdontologosService } from '../../../../services/odontologos.service';
import { PedidosService } from '../../../../services/pedidos.service';
import { ComprobanteResponse, FinanzasService } from '../../../../services/finanzas.service';
import { AuthService } from '../../../../services/auth';

/**
 * Si la consulta de finanzas falla, la pantalla NO puede mostrar "no debe nada": antes un error de red se
 * convertía en saldo $0 y lista vacía, y la administrativa podía creer que el odontólogo estaba al día.
 */
describe('OdontologoHistorialComponent: un error de finanzas no se disfraza de "debe $0"', () => {
  let fixture: ComponentFixture<OdontologoHistorialComponent>;
  let c: OdontologoHistorialComponent;
  let toastr: jasmine.SpyObj<ToastrService>;
  let finanzas: jasmine.SpyObj<FinanzasService>;

  const falla = () => throwError(() => new HttpErrorResponse({ status: 503 }));
  const comprobante = { id: 1, monto: 50000, saldoPendiente: 50000 };

  beforeEach(() => {
    toastr = jasmine.createSpyObj<ToastrService>('ToastrService', ['success', 'error', 'info', 'warning']);
    finanzas = jasmine.createSpyObj<FinanzasService>('FinanzasService',
      ['saldoPorOdontologo', 'comprobantesPorOdontologo', 'historialPagosOdontologo']);
    finanzas.saldoPorOdontologo.and.returnValue(of(50000));
    finanzas.comprobantesPorOdontologo.and.returnValue(of([comprobante] as unknown as ComprobanteResponse[]));
    finanzas.historialPagosOdontologo.and.returnValue(of([]));

    TestBed.configureTestingModule({
      imports: [OdontologoHistorialComponent],
      providers: [
        provideRouter([]),
        { provide: ToastrService, useValue: toastr },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: '5' }) } } },
        { provide: OdontologosService, useValue: { buscarPorId: () => of({ id: 5, nombre: 'Dr. Pérez' }) } },
        { provide: PedidosService, useValue: { listarTodos: () => of([]) } },
        { provide: FinanzasService, useValue: finanzas },
        { provide: AuthService, useValue: { puedeVerFinanzas: () => true } },
      ],
    });
    fixture = TestBed.createComponent(OdontologoHistorialComponent);
    c = fixture.componentInstance;
  });

  const recargar = (silencioso: boolean) => (c as unknown as { cargar(id: number, s: boolean): void }).cargar(5, silencioso);

  it('con todo en orden muestra la deuda real', () => {
    fixture.detectChanges();

    expect(c.saldoDeuda()).toBe(50000);
    expect(c.comprobantes().length).toBe(1);
    expect(toastr.warning).not.toHaveBeenCalled();
  });

  it('si falla finanzas al entrar, avisa que los montos pueden estar desactualizados', () => {
    finanzas.saldoPorOdontologo.and.returnValue(falla());
    finanzas.comprobantesPorOdontologo.and.returnValue(falla());

    fixture.detectChanges();

    expect(toastr.warning).toHaveBeenCalledWith(jasmine.stringMatching('desactualizados'), 'Datos incompletos');
  });

  it('si falla una actualización de fondo, se conserva la deuda que ya se veía y no se pone en cero', () => {
    fixture.detectChanges();
    expect(c.saldoDeuda()).toBe(50000);

    finanzas.saldoPorOdontologo.and.returnValue(falla());
    finanzas.comprobantesPorOdontologo.and.returnValue(falla());
    finanzas.historialPagosOdontologo.and.returnValue(falla());
    toastr.warning.calls.reset();
    recargar(true);

    expect(c.saldoDeuda()).toBe(50000);
    expect(c.comprobantes().length).toBe(1);
    expect(toastr.warning).not.toHaveBeenCalled();   // en segundo plano no se llena la pantalla de avisos cada vez que se consulta
  });

  it('tras registrar un pago, si no se pudo refrescar la cuenta, lo dice una sola vez', () => {
    fixture.detectChanges();
    finanzas.saldoPorOdontologo.and.returnValue(falla());
    finanzas.comprobantesPorOdontologo.and.returnValue(falla());
    finanzas.historialPagosOdontologo.and.returnValue(falla());

    c.onPagoRegistrado();

    expect(toastr.warning).toHaveBeenCalledWith(jasmine.stringMatching('el pago se registró'.replace('el', 'El')), 'Datos desactualizados');
  });
});
