import { HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { EscaneosComponent } from './escaneos';
import { EscaneoResponse, EscaneosService } from '../../../services/escaneos.service';
import { PedidoResponse, PedidosService } from '../../../services/pedidos.service';

/**
 * Antes un escaneo rechazado por el servidor (cortado, con datos inválidos, demasiado pesado) simplemente
 * desaparecía de la pantalla: el error se tragaba y no había ningún aviso. Estas pruebas fijan que se vea.
 */
describe('EscaneosComponent: avisos al usuario', () => {
  let fixture: ComponentFixture<EscaneosComponent>;
  let componente: EscaneosComponent;
  let toastr: jasmine.SpyObj<ToastrService>;
  let escaneos: jasmine.SpyObj<EscaneosService>;

  const rechazo = (mensaje: string, status = 400) =>
    throwError(() => new HttpErrorResponse({ status, error: { mensaje } }));
  const aceptado = (fileName: string, advertencias: string[] = []) =>
    of(new HttpResponse<EscaneoResponse>({ status: 201, body: { id: 1, fileName, advertencias } as EscaneoResponse }));

  beforeEach(() => {
    toastr = jasmine.createSpyObj<ToastrService>('ToastrService', ['success', 'error', 'info', 'warning']);
    escaneos = jasmine.createSpyObj<EscaneosService>('EscaneosService', ['listar', 'subir', 'eliminar', 'descargar']);
    escaneos.listar.and.returnValue(of([]));
    TestBed.configureTestingModule({
      imports: [EscaneosComponent],
      providers: [
        { provide: ToastrService, useValue: toastr },
        { provide: EscaneosService, useValue: escaneos },
        { provide: PedidosService, useValue: { listarTodos: () => of([]) } },
      ],
    });
    fixture = TestBed.createComponent(EscaneosComponent);
    componente = fixture.componentInstance;
    componente.pedidoSeleccionado.set({ id: 9, nroPedido: 'PED-9' } as PedidoResponse);
  });

  const subir = (...archivos: File[]) => (componente as unknown as { subirArchivos(f: File[]): void }).subirArchivos(archivos);
  const stl = (nombre: string) => new File(['x'], nombre);

  it('un archivo rechazado muestra su nombre y el motivo que dio el servidor', () => {
    escaneos.subir.and.returnValue(rechazo('El archivo está cortado: faltan 12 triángulos'));

    subir(stl('roto.stl'));

    expect(toastr.error).toHaveBeenCalledWith(
      'roto.stl: El archivo está cortado: faltan 12 triángulos', 'Escaneo rechazado', jasmine.anything());
    expect(componente.subiendo()).toBeFalse();
  });

  it('si uno de dos se rechaza, se sube el otro y se avisa que la subida quedó incompleta', () => {
    escaneos.subir.and.returnValues(rechazo('El archivo está cortado'), aceptado('bien.stl'));

    subir(stl('roto.stl'), stl('bien.stl'));

    expect(componente.escaneos().map(e => e.fileName)).toEqual(['bien.stl']);
    expect(toastr.warning).toHaveBeenCalledWith(
      jasmine.stringMatching('Se subieron 1 de 2 escaneos. Uno fue rechazado'), 'Subida incompleta');
  });

  it('cuando todo sale bien, lo confirma', () => {
    escaneos.subir.and.returnValues(aceptado('a.stl'), aceptado('b.stl'));

    subir(stl('a.stl'), stl('b.stl'));

    expect(toastr.success).toHaveBeenCalledWith('2 escaneos subidos', undefined);
    expect(toastr.error).not.toHaveBeenCalled();
  });

  it('lo que el servidor dejó pasar pero vio raro se muestra al subir, no después', () => {
    escaneos.subir.and.returnValue(aceptado('arcada.stl', ['Las medidas parecen estar en metros', 'Hay 3 caras degeneradas']));

    subir(stl('arcada.stl'));

    expect(toastr.warning).toHaveBeenCalledWith(
      'arcada.stl: Las medidas parecen estar en metros (y 1 aviso más)', 'Se subió, pero revisalo');
  });

  it('si el servidor no acepta un archivo por pesado, lo dice en vez de "Error 413"', () => {
    escaneos.subir.and.returnValue(throwError(() => new HttpErrorResponse({ status: 413, error: '<html>413</html>' })));

    subir(stl('enorme.stl'));

    expect(toastr.error).toHaveBeenCalledWith('enorme.stl: el archivo es demasiado grande', 'Escaneo rechazado', jasmine.anything());
  });

  it('borrar un escaneo confirma, y si falla avisa y lo deja en la lista', () => {
    const e = { id: 4, fileName: 'a.stl' } as EscaneoResponse;
    componente.escaneos.set([e]);

    escaneos.eliminar.and.returnValue(rechazo('No se pudo borrar del almacenamiento', 503));
    componente.eliminar(e);
    expect(toastr.error).toHaveBeenCalled();
    expect(componente.escaneos()).toEqual([e]);

    escaneos.eliminar.and.returnValue(of(undefined as unknown as void));
    componente.eliminar(e);
    expect(toastr.success).toHaveBeenCalledWith('Escaneo eliminado', undefined);
    expect(componente.escaneos()).toEqual([]);
  });
});
