// Cola de envío de los avisos "tu pedido está listo". Pura (el reloj, el azar y el envío se inyectan)
// para poder probarla con un reloj falso: test/envios.test.js.
//
// Por qué existe: antes, cada pedido que pasaba a LISTO disparaba un mensaje al instante — sin
// pausas, sin tope, a cualquier hora, y si un odontólogo tenía 3 pedidos listos recibía 3 mensajes
// idénticos seguidos. Eso es justo el patrón que WhatsApp asocia con automatización (mensajes
// idénticos a ráfagas) y que además molesta a quien los recibe. La cola:
//   - junta los avisos de un mismo odontólogo (ventana de unos minutos) en UN solo mensaje;
//   - espera una pausa al azar entre un envío y el siguiente;
//   - respeta un tope por hora y un horario (no avisa de madrugada);
//   - se guarda en disco: un reinicio del bot no pierde los avisos pendientes;
//   - reintenta con espera si el envío falla, y desiste después de unos intentos.
'use strict';

const DEFECTOS = {
  fusionMs: 3 * 60 * 1000,          // espera para juntar avisos del mismo odontólogo
  pausaMinMs: 25 * 1000,            // pausa entre envíos: al azar entre mín y máx
  pausaMaxMs: 75 * 1000,
  topePorHora: 15,
  horaDesde: 8,                     // solo se envía entre horaDesde y horaHasta (hora local)
  horaHasta: 21,
  zonaHoraria: 'America/Argentina/Buenos_Aires',
  maxIntentos: 4,
  esperaReintentoMs: 5 * 60 * 1000,
};

function horaLocal(ms, zona) {
  return Number(new Intl.DateTimeFormat('en-GB', { hour: 'numeric', hour12: false, timeZone: zona }).format(new Date(ms))) % 24;
}

/** Texto del aviso: uno solo para un pedido, uno que los lista si son varios. */
function armarTexto({ nombre, pedidos }) {
  const saludo = `Hola ${nombre || 'Dr./Dra.'}`;
  if (pedidos.length === 1) {
    const p = pedidos[0];
    return `*Laboratorio GS*\n${saludo}, su trabajo *${p.trabajo || 'trabajo solicitado'}* ` +
      `(pedido *${p.nroPedido}*) ya está listo para retirar.\n_Por favor coordine el retiro con el laboratorio._`;
  }
  const lista = pedidos.map(p => `• ${p.trabajo || 'trabajo'} (pedido *${p.nroPedido}*)`).join('\n');
  return `*Laboratorio GS*\n${saludo}, estos trabajos ya están listos para retirar:\n${lista}\n` +
    `_Por favor coordine el retiro con el laboratorio._`;
}

/**
 * @param {object} deps
 * @param {(chatId: string, texto: string) => Promise<void>} deps.enviar
 * @param {() => number} [deps.ahora]
 * @param {() => number} [deps.aleatorio]     devuelve [0,1)
 * @param {() => object} [deps.cargar]        estado guardado (cola + historial) o {}
 * @param {(estado: object) => void} [deps.guardar]
 * @param {object} [deps.opciones]            ver DEFECTOS
 */
function crearEnvios({ enviar, ahora = Date.now, aleatorio = Math.random, cargar = () => ({}), guardar = () => {}, opciones = {} }) {
  const o = { ...DEFECTOS, ...opciones };
  const guardado = cargar() || {};
  /** chatId -> { chatId, nombre, pedidos: [{nroPedido, trabajo}], listoEn, intentos } */
  const cola = new Map((guardado.cola || []).map(e => [e.chatId, e]));
  let enviados = (guardado.enviados || []).filter(t => ahora() - t < 3600 * 1000);
  let proximoEnvioEn = guardado.proximoEnvioEn || 0;

  const persistir = () => guardar({ cola: [...cola.values()], enviados, proximoEnvioEn });

  function encolar({ chatId, nombre, nroPedido, trabajo }) {
    const existente = cola.get(chatId);
    if (existente) {
      if (existente.pedidos.some(p => p.nroPedido === nroPedido)) { return { estado: 'ya-en-cola', enviarEn: existente.listoEn }; }
      existente.pedidos.push({ nroPedido, trabajo });
      if (nombre) existente.nombre = nombre;
      persistir();
      return { estado: 'fusionado', enviarEn: existente.listoEn };
    }
    const entrada = { chatId, nombre, pedidos: [{ nroPedido, trabajo }], listoEn: ahora() + o.fusionMs, intentos: 0 };
    cola.set(chatId, entrada);
    persistir();
    return { estado: 'encolado', enviarEn: entrada.listoEn };
  }

  /** Manda lo que corresponda ahora (a lo sumo UN mensaje por llamada). Devuelve qué hizo. */
  async function procesar() {
    const t = ahora();
    if (!cola.size) return { hizo: 'nada' };
    const h = horaLocal(t, o.zonaHoraria);
    if (h < o.horaDesde || h >= o.horaHasta) return { hizo: 'fuera-de-horario' };
    if (t < proximoEnvioEn) return { hizo: 'esperando-pausa' };
    enviados = enviados.filter(x => t - x < 3600 * 1000);
    if (enviados.length >= o.topePorHora) return { hizo: 'tope-por-hora' };

    const lista = [...cola.values()].filter(e => e.listoEn <= t).sort((a, b) => a.listoEn - b.listoEn);
    if (!lista.length) return { hizo: 'juntando' };
    const e = lista[0];

    try {
      await enviar(e.chatId, armarTexto(e));
    } catch (err) {
      e.intentos += 1;
      if (e.intentos >= o.maxIntentos) {
        cola.delete(e.chatId);
        persistir();
        return { hizo: 'descartado', chatId: e.chatId, error: err.message };
      }
      e.listoEn = t + o.esperaReintentoMs;
      persistir();
      return { hizo: 'reintentar', chatId: e.chatId, error: err.message };
    }
    cola.delete(e.chatId);
    enviados.push(t);
    proximoEnvioEn = t + o.pausaMinMs + Math.floor(aleatorio() * (o.pausaMaxMs - o.pausaMinMs));
    persistir();
    return { hizo: 'enviado', chatId: e.chatId, pedidos: e.pedidos.length };
  }

  return {
    encolar, procesar,
    pendientes: () => [...cola.values()].map(e => ({ chatId: e.chatId, pedidos: e.pedidos.length, listoEn: e.listoEn })),
    estado: () => ({ enCola: cola.size, enviadosUltimaHora: enviados.length, proximoEnvioEn }),
  };
}

module.exports = { crearEnvios, armarTexto, DEFECTOS };
