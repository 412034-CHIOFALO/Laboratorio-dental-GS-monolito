import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { AsistenciaModalComponent } from './asistencia-modal.component';
import { AsistenciaMes, DiaAsistencia, EmpleadoSueldo, SueldosService } from '../../../../services/sueldos.service';

/**
 * Elegir un rango de días: en una computadora se hace con Mayús; en un celular no existe esa tecla, así que hay
 * un modo "Elegir rango" (primer toque = inicio, segundo toque = fin). Las dos formas tienen que dar lo mismo.
 */
describe('AsistenciaModalComponent: selección de rango', () => {
  let fixture: ComponentFixture<AsistenciaModalComponent>;
  let c: AsistenciaModalComponent;

  const dia = (n: number, enRelacion = true): DiaAsistencia =>
    ({ fecha: `2026-10-${String(n).padStart(2, '0')}`, tipo: null, sePaga: true, enRelacion });
  const mes = (): AsistenciaMes => ({
    mes: '2026-10', empleadoId: 1, empleadoNombre: 'Rebeca', frecuencia: 'MENSUAL', fechaAlta: '2026-01-01', fechaBaja: null,
    // el día 6 queda fuera de la relación laboral (por ejemplo, antes del alta): nunca debe entrar en un rango
    dias: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map(n => dia(n, n !== 6)),
    resumen: { diasPagos: 9, diasNoPagos: 0, trabajados: 0, ausencias: 0, vacaciones: 0, feriados: 0 } as AsistenciaMes['resumen'],
  });
  const click = (n: number, shift = false) => c.clickDia(dia(n), { shiftKey: shift } as MouseEvent);
  const elegidos = () => [...c.seleccion()].sort().map(f => Number(f.slice(8)));

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AsistenciaModalComponent],
      providers: [
        { provide: ToastrService, useValue: jasmine.createSpyObj('ToastrService', ['success', 'error', 'info', 'warning']) },
        { provide: SueldosService, useValue: { asistenciaMes: () => of(mes()) } },
      ],
    });
    fixture = TestBed.createComponent(AsistenciaModalComponent);
    fixture.componentRef.setInput('empleado', { usuarioId: 1, nombre: 'Rebeca' } as EmpleadoSueldo);
    c = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('sin ningún modo, cada toque marca o desmarca un día', () => {
    click(3); click(5); click(3);
    expect(elegidos()).toEqual([5]);
  });

  it('con el dedo: "Elegir rango", primer toque inicio, segundo toque fin', () => {
    c.alternarRango();
    expect(c.ayudaSeleccion()).toBe('Tocá el primer día del rango.');
    click(2);
    expect(c.inicioRango()).toBe('2026-10-02');
    expect(c.ayudaSeleccion()).toBe('Ahora tocá el último día del rango.');
    click(4);
    expect(elegidos()).toEqual([2, 3, 4]);
  });

  it('al cerrar el rango vuelve solo a elegir día por día', () => {
    c.alternarRango();
    click(2); click(4);
    expect(c.modoRango()).toBeFalse();
    expect(c.inicioRango()).toBeNull();
    click(9);
    expect(elegidos()).toEqual([2, 3, 4, 9]);
  });

  it('el rango se puede armar de atrás para adelante', () => {
    c.alternarRango();
    click(8); click(7);
    expect(elegidos()).toEqual([7, 8]);
  });

  it('un día fuera de la relación laboral nunca entra en un rango', () => {
    c.alternarRango();
    click(5); click(8);
    expect(elegidos()).toEqual([5, 7, 8]);
  });

  it('con mouse sigue funcionando Mayús', () => {
    click(2);
    click(5, true);
    expect(elegidos()).toEqual([2, 3, 4, 5]);
  });

  it('apagar el modo a mitad de camino descarta el inicio pero no lo ya elegido', () => {
    click(1);
    c.alternarRango();
    click(3);
    c.alternarRango();
    expect(c.modoRango()).toBeFalse();
    expect(c.inicioRango()).toBeNull();
    expect(elegidos()).toEqual([1, 3]);
  });

  it('"Limpiar" también apaga el modo rango', () => {
    c.alternarRango();
    click(2);
    c.limpiarSeleccion();
    expect(c.modoRango()).toBeFalse();
    expect(elegidos()).toEqual([]);
  });
});
