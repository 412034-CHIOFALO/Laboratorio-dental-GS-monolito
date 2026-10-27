import { aCsv } from './csv.util';

describe('aCsv', () => {
  it('separa con punto y coma, usa coma decimal y termina cada línea con salto de Windows', () => {
    expect(aCsv(['Nombre', 'Monto'], [['Dr. Pérez', 57500.5], ['Dra. Sosa', 0]]))
      .toBe('Nombre;Monto\r\nDr. Pérez;57500,5\r\nDra. Sosa;0\r\n');
  });

  it('entrecomilla lo que trae separadores, comillas o saltos de línea', () => {
    expect(aCsv(['a'], [['uno; dos'], ['dijo "hola"'], ['línea\nnueva']]))
      .toBe('a\r\n"uno; dos"\r\n"dijo ""hola"""\r\n"línea\nnueva"\r\n');
  });

  it('lo que parece una fórmula no se ejecuta en Excel', () => {
    expect(aCsv(['a'], [['=HYPERLINK("http://malo")'], ['+54 11 1234'], ['@usuario'], ['-cmd']]))
      .toBe('a\r\n"\'=HYPERLINK(""http://malo"")"\r\n\'+54 11 1234\r\n\'@usuario\r\n\'-cmd\r\n');
  });

  it('un número negativo sigue siendo un número', () => {
    expect(aCsv(['a'], [[-1500]])).toBe('a\r\n-1500\r\n');
  });

  it('los vacíos y los números no finitos quedan en blanco', () => {
    expect(aCsv(['a', 'b', 'c'], [[null, undefined, NaN]])).toBe('a;b;c\r\n;;\r\n');
  });
});
