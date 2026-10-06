'use strict';
// Cola de avisos "pedido listo" + normalización de teléfonos argentinos. Reloj falso: sin esperas reales.
const test = require('node:test');
const assert = require('node:assert/strict');
const { crearEnvios, armarTexto } = require('../envios');
const { normalizarTelefonoAR } = require('../telefono');

// 2026-10-06 12:00 hora de Argentina (UTC-3) = 15:00 UTC
const MEDIODIA = Date.UTC(2026, 9, 6, 15, 0, 0);
const MIN = 60 * 1000;

function entorno(opciones = {}, estadoInicial = {}) {
  const reloj = { t: MEDIODIA };
  const enviados = [];
  let guardado = estadoInicial;
  const falla = { n: 0 };
  const envios = crearEnvios({
    enviar: async (chatId, texto) => { if (falla.n > 0) { falla.n--; throw new Error('sin conexión'); } enviados.push({ chatId, texto }); },
    ahora: () => reloj.t,
    aleatorio: () => 0.5,
    cargar: () => guardado,
    guardar: (e) => { guardado = JSON.parse(JSON.stringify(e)); },
    opciones,
  });
  return { reloj, enviados, envios, falla, estado: () => guardado };
}

test('un aviso espera la ventana de fusión y después sale', async () => {
  const { reloj, enviados, envios } = entorno();
  envios.encolar({ chatId: 'A@c.us', nombre: 'Dr. Pérez', nroPedido: 'P-1', trabajo: 'Corona' });
  assert.equal((await envios.procesar()).hizo, 'juntando');
  reloj.t += 3 * MIN + 1;
  assert.equal((await envios.procesar()).hizo, 'enviado');
  assert.equal(enviados.length, 1);
  assert.match(enviados[0].texto, /Corona.*P-1/s);
});

test('varios pedidos del mismo odontólogo salen en UN solo mensaje', async () => {
  const { reloj, enviados, envios } = entorno();
  envios.encolar({ chatId: 'A@c.us', nombre: 'Dr. Pérez', nroPedido: 'P-1', trabajo: 'Corona' });
  reloj.t += MIN;
  assert.equal(envios.encolar({ chatId: 'A@c.us', nombre: 'Dr. Pérez', nroPedido: 'P-2', trabajo: 'Puente' }).estado, 'fusionado');
  reloj.t += 3 * MIN;
  await envios.procesar();
  assert.equal(enviados.length, 1);
  assert.match(enviados[0].texto, /estos trabajos/);
  assert.match(enviados[0].texto, /P-1/);
  assert.match(enviados[0].texto, /P-2/);
});

test('el mismo pedido encolado dos veces no se duplica', async () => {
  const { envios } = entorno();
  envios.encolar({ chatId: 'A@c.us', nroPedido: 'P-1', trabajo: 'Corona' });
  assert.equal(envios.encolar({ chatId: 'A@c.us', nroPedido: 'P-1', trabajo: 'Corona' }).estado, 'ya-en-cola');
  assert.equal(envios.pendientes()[0].pedidos, 1);
});

test('entre dos envíos hay una pausa; los destinatarios distintos no salen en ráfaga', async () => {
  const { reloj, enviados, envios } = entorno({ pausaMinMs: 30 * 1000, pausaMaxMs: 30 * 1000 });
  envios.encolar({ chatId: 'A@c.us', nroPedido: 'P-1', trabajo: 'x' });
  envios.encolar({ chatId: 'B@c.us', nroPedido: 'P-2', trabajo: 'y' });
  reloj.t += 3 * MIN + 1;
  assert.equal((await envios.procesar()).hizo, 'enviado');
  assert.equal((await envios.procesar()).hizo, 'esperando-pausa');
  assert.equal(enviados.length, 1);
  reloj.t += 31 * 1000;
  assert.equal((await envios.procesar()).hizo, 'enviado');
  assert.deepEqual(enviados.map(e => e.chatId), ['A@c.us', 'B@c.us']);
});

test('tope por hora: lo que excede espera', async () => {
  const { reloj, enviados, envios } = entorno({ topePorHora: 2, pausaMinMs: 1, pausaMaxMs: 1 });
  for (const c of ['A', 'B', 'C']) envios.encolar({ chatId: `${c}@c.us`, nroPedido: `P-${c}`, trabajo: 't' });
  reloj.t += 3 * MIN + 1;
  await envios.procesar(); reloj.t += 5; await envios.procesar(); reloj.t += 5;
  assert.equal((await envios.procesar()).hizo, 'tope-por-hora');
  assert.equal(enviados.length, 2);
  reloj.t += 61 * MIN;                             // pasó la hora
  assert.equal((await envios.procesar()).hizo, 'enviado');
});

test('no se envía de madrugada: espera hasta las 8', async () => {
  const { reloj, enviados, envios } = entorno();
  reloj.t = Date.UTC(2026, 9, 6, 5, 30, 0);          // 02:30 en Argentina
  envios.encolar({ chatId: 'A@c.us', nroPedido: 'P-1', trabajo: 'x' });
  reloj.t += 4 * MIN;
  assert.equal((await envios.procesar()).hizo, 'fuera-de-horario');
  reloj.t = Date.UTC(2026, 9, 6, 11, 5, 0);          // 08:05
  assert.equal((await envios.procesar()).hizo, 'enviado');
  assert.equal(enviados.length, 1);
});

test('si el envío falla se reintenta más tarde y, tras varios intentos, se descarta', async () => {
  const { reloj, enviados, envios, falla } = entorno({ maxIntentos: 3, esperaReintentoMs: 5 * MIN });
  envios.encolar({ chatId: 'A@c.us', nroPedido: 'P-1', trabajo: 'x' });
  reloj.t += 3 * MIN + 1;
  falla.n = 1;
  assert.equal((await envios.procesar()).hizo, 'reintentar');
  reloj.t += 5 * MIN + 1;
  assert.equal((await envios.procesar()).hizo, 'enviado');   // el segundo intento anda
  assert.equal(enviados.length, 1);

  envios.encolar({ chatId: 'B@c.us', nroPedido: 'P-2', trabajo: 'y' });
  falla.n = 99;
  for (let i = 0; i < 3; i++) { reloj.t += 6 * MIN; await envios.procesar(); reloj.t += 3 * MIN; }
  assert.equal(envios.pendientes().length, 0, 'tras maxIntentos se descarta para no reintentar eternamente');
});

test('la cola sobrevive a un reinicio del bot', async () => {
  const a = entorno();
  a.envios.encolar({ chatId: 'A@c.us', nombre: 'Dr. Pérez', nroPedido: 'P-1', trabajo: 'Corona' });
  const b = entorno({}, a.estado());               // "reinicio": se crea de nuevo con lo que quedó guardado
  assert.equal(b.envios.pendientes().length, 1);
  b.reloj.t += 3 * MIN + 1;
  assert.equal((await b.envios.procesar()).hizo, 'enviado');
  assert.equal(b.enviados.length, 1);
});

test('texto: un pedido y varios pedidos', () => {
  assert.match(armarTexto({ nombre: 'Dra. Ruiz', pedidos: [{ nroPedido: 'P-9', trabajo: 'Carilla' }] }), /Hola Dra\. Ruiz.*Carilla.*P-9/s);
  assert.match(armarTexto({ pedidos: [{ nroPedido: 'P-1', trabajo: 'a' }, { nroPedido: 'P-2', trabajo: 'b' }] }), /Hola Dr\.\/Dra\./);
});

test('teléfonos argentinos: todas las formas de escribirlo dan el mismo número de WhatsApp', () => {
  const esperado = '5493516588576@c.us';
  for (const entrada of ['+54 9 351 658-8576', '5493516588576', '54 351 6588576', '0351 15 6588576', '0351-6588576',
                         '351 6588576', '3516588576', '15 6588576', '6588576', '(0351) 15-658-8576', '0054 9 351 6588576']) {
    assert.equal(normalizarTelefonoAR(entrada)?.jid, esperado, `entrada: ${entrada}`);
  }
});

test('teléfonos de Buenos Aires (área de 2 dígitos) y con 15', () => {
  assert.equal(normalizarTelefonoAR('011 15 4567-8901').jid, '5491145678901@c.us');
  assert.equal(normalizarTelefonoAR('+54 9 11 4567-8901').jid, '5491145678901@c.us');
  assert.equal(normalizarTelefonoAR('11 4567 8901').jid, '5491145678901@c.us');
});

test('teléfonos imposibles devuelven null en vez de un número inventado', () => {
  for (const x of ['', null, 'abc', '123', '12345678901234567', '0000000000']) assert.equal(normalizarTelefonoAR(x), null, String(x));
});

test('el área por defecto se puede cambiar', () => {
  assert.equal(normalizarTelefonoAR('4567-8901', '11').jid, '5491145678901@c.us');
});
