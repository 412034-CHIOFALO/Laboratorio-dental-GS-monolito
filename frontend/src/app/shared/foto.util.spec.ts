import { FOTO_LADO_MAXIMO, kbDeDataUrl, tamanioReducido } from './foto.util';

describe('tamanioReducido', () => {
  it('una foto de celular horizontal 4:3 queda con el lado largo en el máximo y la misma proporción', () => {
    expect(tamanioReducido(4032, 3024)).toEqual({ ancho: FOTO_LADO_MAXIMO, alto: 750 });
  });

  it('una 16:9 también', () => {
    expect(tamanioReducido(3840, 2160)).toEqual({ ancho: FOTO_LADO_MAXIMO, alto: 563 });
  });

  it('una foto vertical se achica por su lado largo, que es el alto', () => {
    expect(tamanioReducido(3024, 4032)).toEqual({ ancho: 750, alto: FOTO_LADO_MAXIMO });
  });

  it('una cuadrada', () => {
    expect(tamanioReducido(2000, 2000)).toEqual({ ancho: FOTO_LADO_MAXIMO, alto: FOTO_LADO_MAXIMO });
  });

  it('una foto que ya es chica no se agranda', () => {
    expect(tamanioReducido(800, 600)).toEqual({ ancho: 800, alto: 600 });
    expect(tamanioReducido(FOTO_LADO_MAXIMO, 300)).toEqual({ ancho: FOTO_LADO_MAXIMO, alto: 300 });
  });

  it('una imagen muy alargada no queda con un lado en cero', () => {
    expect(tamanioReducido(20000, 3)).toEqual({ ancho: FOTO_LADO_MAXIMO, alto: 1 });
  });
});

describe('kbDeDataUrl', () => {
  it('calcula el peso real de la imagen y no el del texto base64', () => {
    const base64 = 'A'.repeat(4096);
    expect(kbDeDataUrl('data:image/jpeg;base64,' + base64)).toBe(3);
  });
});
