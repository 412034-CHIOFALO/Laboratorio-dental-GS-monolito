import { DatosPedidoSolicitud, enumerar, faltantesDelPedido } from './solicitudes.validacion';

/** Hoy es martes 27/10/2026: la primera fecha de entrega posible es el miércoles 28. */
const MINIMA = '2026-10-28';

const completo = (d: Partial<DatosPedidoSolicitud> = {}): DatosPedidoSolicitud => ({
  odontologoId: 7, odontologoNombre: 'Dr. Pérez', paciente: 'Ana Gómez', trabajo: 'Corona sin cofia', eligioOtroTrabajo: false,
  fechaEntrega: '2026-11-04', precio: 85000, ...d,
});

const resumenes = (d: Partial<DatosPedidoSolicitud>) => faltantesDelPedido(completo(d), MINIMA).map(f => f.resumen);

describe('faltantesDelPedido', () => {
  it('un pedido completo no tiene faltantes', () => {
    expect(faltantesDelPedido(completo(), MINIMA)).toEqual([]);
  });

  it('el caso de un mail que no dice el paciente: avisa que falta el paciente y dónde', () => {
    const f = faltantesDelPedido(completo({ paciente: '' }), MINIMA);

    expect(f.length).toBe(1);
    expect(f[0].resumen).toBe('el paciente');
    expect(f[0].campoId).toBe('paciente');
    expect(f[0].mensaje).toContain('paciente');
  });

  it('un paciente de solo espacios cuenta como vacío', () => {
    expect(resumenes({ paciente: '   ' })).toEqual(['el paciente']);
  });

  it('el precio NO es obligatorio: sin precio se puede crear', () => {
    expect(resumenes({ precio: null })).toEqual([]);
    expect(resumenes({ precio: 0 })).toEqual([]);
  });

  it('un precio negativo se rechaza', () => {
    expect(resumenes({ precio: -1 })).toEqual(['un precio que no sea negativo']);
  });

  it('el odontólogo vale con el id de la lista o con un nombre escrito, pero no con nada', () => {
    expect(resumenes({ odontologoId: null, odontologoNombre: 'Dra. Nueva' })).toEqual([]);
    expect(resumenes({ odontologoId: 7, odontologoNombre: '' })).toEqual([]);
    expect(resumenes({ odontologoId: null, odontologoNombre: '  ' })).toEqual(['el odontólogo']);
  });

  it('sin trabajo elegido pide elegirlo; con «Otro trabajo» pide escribirlo, en el campo que corresponde', () => {
    const sinElegir = faltantesDelPedido(completo({ trabajo: '' }), MINIMA)[0];
    const otro = faltantesDelPedido(completo({ trabajo: '', eligioOtroTrabajo: true }), MINIMA)[0];

    expect(sinElegir.resumen).toBe('el trabajo');
    expect(sinElegir.campoId).toBe('trabajo-cat');
    expect(otro.resumen).toBe('qué trabajo es');
    expect(otro.campoId).toBe('trabajo');
  });

  it('la fecha: vacía, de hoy o del pasado no sirven; de mañana en adelante sí', () => {
    expect(resumenes({ fechaEntrega: '' })).toEqual(['la fecha de entrega']);
    expect(resumenes({ fechaEntrega: '2026-10-27' })).toEqual(['una fecha de entrega de mañana en adelante']);
    expect(resumenes({ fechaEntrega: '2026-01-01' })).toEqual(['una fecha de entrega de mañana en adelante']);
    expect(resumenes({ fechaEntrega: '2026-10-28' })).toEqual([]);
  });

  it('varios faltantes salen en el orden del formulario', () => {
    expect(resumenes({ odontologoId: null, odontologoNombre: '', paciente: '', trabajo: '', fechaEntrega: '' }))
      .toEqual(['el odontólogo', 'el paciente', 'el trabajo', 'la fecha de entrega']);
  });
});

describe('enumerar', () => {
  it('arma la lista en castellano', () => {
    expect(enumerar([])).toBe('');
    expect(enumerar(['el paciente'])).toBe('el paciente');
    expect(enumerar(['el paciente', 'la fecha de entrega'])).toBe('el paciente y la fecha de entrega');
    expect(enumerar(['el odontólogo', 'el paciente', 'el trabajo'])).toBe('el odontólogo, el paciente y el trabajo');
  });
});
