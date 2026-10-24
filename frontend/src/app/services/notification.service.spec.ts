import { TestBed } from '@angular/core/testing';
import { ToastrService } from 'ngx-toastr';
import { NotificationService } from './notification.service';

describe('NotificationService', () => {
  let toastr: jasmine.SpyObj<ToastrService>;
  let service: NotificationService;

  beforeEach(() => {
    toastr = jasmine.createSpyObj<ToastrService>('ToastrService', ['success', 'error', 'info', 'warning']);
    TestBed.configureTestingModule({ providers: [{ provide: ToastrService, useValue: toastr }] });
    service = TestBed.inject(NotificationService);
  });

  describe('mensajeHttp', () => {
    it('devuelve lo que dijo el backend', () => {
      const err = { status: 400, error: { mensaje: 'El archivo está cortado: faltan 12 triángulos' } };
      expect(service.mensajeHttp(err, 'no se pudo subir')).toBe('El archivo está cortado: faltan 12 triángulos');
    });

    it('junta los campos inválidos en una sola línea', () => {
      const err = { status: 400, error: { campos: [{ campo: 'precio', mensaje: 'no puede ser negativo' }, { campo: 'nombre', mensaje: 'es obligatorio' }] } };
      expect(service.mensajeHttp(err, 'x')).toContain('no puede ser negativo');
      expect(service.mensajeHttp(err, 'x')).toContain('es obligatorio');
    });

    it('un archivo que nginx corta por pesado se explica sin mostrar "Error 413"', () => {
      expect(service.mensajeHttp({ status: 413, error: '<html>413 Request Entity Too Large</html>' }, 'x')).toBe('el archivo es demasiado grande');
    });

    it('sin conexión lo dice; sin pistas usa el texto de respaldo', () => {
      expect(service.mensajeHttp({ status: 0 }, 'x')).toBe('no hay conexión con el servidor');
      expect(service.mensajeHttp({ status: 500, error: {} }, 'no se pudo subir')).toBe('no se pudo subir');
    });
  });

  describe('errorHttp', () => {
    it('un 413 muestra un aviso propio y no el título crudo del servidor', () => {
      service.errorHttp({ status: 413, error: '<html></html>' }, 'x');
      expect(toastr.error).toHaveBeenCalledWith(jasmine.stringMatching('más grande'), 'Archivo demasiado grande', jasmine.anything());
    });

    it('sin conexión ya no habla de microservicios', () => {
      service.errorHttp({ status: 0 }, 'x');
      const mensaje = toastr.error.calls.mostRecent().args[0] as string;
      expect(mensaje).not.toContain('microservicio');
      expect(mensaje).toContain('conexión');
    });
  });
});
