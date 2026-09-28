/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  Bot de WhatsApp — Laboratorio GS
 *
 *  Escucha los grupos de comprobantes, detecta los pagos triangulados y los
 *  registra en el sistema. Maneja dos formas de mandar el comprobante:
 *
 *    1) Comprobante + "Emisor (Receptor)" en el MISMO mensaje (caption)
 *    2) Comprobante en un mensaje, y "Emisor (Receptor)" en el SIGUIENTE
 *
 *  Lee el monto y el N° de operación del comprobante (PDF o imagen) y valida
 *  que el receptor del pie coincida con el "Para" del comprobante.
 *
 *  La sesión se guarda con LocalAuth → escaneás el QR UNA sola vez.
 * ═══════════════════════════════════════════════════════════════════════════
 */

require('dotenv').config();
const { Client, LocalAuth } = require('whatsapp-web.js');
const qrcode = require('qrcode-terminal');
const axios = require('axios');
const { createWorker } = require('tesseract.js');
const pdfParse = require('pdf-parse/lib/pdf-parse.js');
const { GoogleGenerativeAI } = require('@google/generative-ai');
const http = require('http');
const QRCode = require('qrcode');
const fs = require('fs');
const path = require('path');

let ocrWorker = null;

// ─── Configuración ──────────────────────────────────────────────────────────
const BACKEND_URL     = process.env.BACKEND_URL || 'http://localhost:8080';
const BACKEND_ENABLED = process.env.BACKEND_ENABLED === 'true';
const BOT_API_KEY     = process.env.BOT_API_KEY || '';
const GRUPOS = (process.env.GRUPOS || '')
  .split(',').map(g => g.trim().toLowerCase()).filter(Boolean);
// Grupos donde TODO mensaje de texto con "Emisor (Receptor) monto" se toma
// directo como pago en efectivo — sin necesitar la palabra "efectivo" ni foto
// de comprobante. Repetir "efectivo" en un mensaje que ya está en el grupo
// "Comprobantes Efectivo" era redundante y confundía a quien lo escribía.
const GRUPOS_EFECTIVO = (process.env.GRUPOS_EFECTIVO || 'comprobantes efectivo')
  .split(',').map(g => g.trim().toLowerCase()).filter(Boolean);

// Mapeo manual opcional "nombre:jid" (separados por coma) para saltear por
// completo la resolución en vivo del nombre del grupo — ver resolverChat()
// más abajo para el porqué. Ej: "comprobantes efectivo:120363...@g.us,..."
const GRUPOS_JIDS = new Map(
  (process.env.GRUPOS_JIDS || '')
    .split(',').map(par => par.trim()).filter(Boolean)
    .map(par => {
      const i = par.lastIndexOf(':');
      return i < 0 ? null : [par.slice(i + 1).trim(), par.slice(0, i).trim()];
    })
    .filter(Boolean)
);

// ─── Gemini (IA para leer cualquier billetera + fotos) ───────────────────────
const GEMINI_API_KEY = process.env.GEMINI_API_KEY || '';
const GEMINI_MODEL   = process.env.GEMINI_MODEL || 'gemini-2.5-flash';
const GEMINI_ENABLED = process.env.GEMINI_ENABLED === 'true' && !!GEMINI_API_KEY;
if (process.env.GEMINI_ENABLED === 'true' && !GEMINI_API_KEY) {
  console.warn('[Bot] ADVERTENCIA: GEMINI_ENABLED=true pero GEMINI_API_KEY está vacía — se usarán reglas locales.');
}
let geminiModel = null;
if (GEMINI_ENABLED) {
  const genAI = new GoogleGenerativeAI(GEMINI_API_KEY);
  geminiModel = genAI.getGenerativeModel({ model: GEMINI_MODEL });
}

// Comprobantes esperando su "Emisor (Receptor)". Clave = grupo|usuario.
const pendientes = new Map();
// Caso inverso: llegó el "Emisor (Receptor)" pero todavía no el comprobante
// (por ejemplo, cuando WhatsApp separa una foto+texto mandados "juntos" en dos
// mensajes distintos, o la persona directamente escribe el pie primero). Sin
// esto, un pie que llega antes que la imagen se perdía en silencio.
const pendientesPie = new Map();
const TIMEOUT_PENDIENTE = 5 * 60 * 1000;   // 5 minutos

// ─── Reconciliación (recuperar comprobantes mandados con el bot caído) ───────
// IDs de mensajes de WhatsApp ya revisados por la reconciliación, persistidos
// en el mismo volumen que la sesión para no repetir OCR/Gemini tras un reinicio
// del contenedor. Guarda solo IDs (livianos), no el contenido de los mensajes.
const RUTA_PROCESADOS = path.join(path.resolve('./.wwebjs_auth/'), 'mensajes-procesados.json');
const MAX_PROCESADOS_GUARDADOS = 3000;

function cargarProcesados() {
  try {
    const data = JSON.parse(fs.readFileSync(RUTA_PROCESADOS, 'utf8'));
    return new Set(Array.isArray(data) ? data : []);
  } catch {
    return new Set();
  }
}

function guardarProcesados() {
  try {
    fs.mkdirSync(path.dirname(RUTA_PROCESADOS), { recursive: true });
    fs.writeFileSync(RUTA_PROCESADOS, JSON.stringify([...mensajesProcesados]));
  } catch (e) {
    console.warn('[Bot] No se pudo guardar mensajes-procesados.json:', e.message);
  }
}

/** Marca un mensaje como visto y recorta el historial guardado si crece demasiado. */
function marcarProcesado(msgId) {
  mensajesProcesados.add(msgId);
  if (mensajesProcesados.size > MAX_PROCESADOS_GUARDADOS * 1.2) {
    mensajesProcesados = new Set([...mensajesProcesados].slice(-MAX_PROCESADOS_GUARDADOS));
  }
  guardarProcesados();
}

let mensajesProcesados = cargarProcesados();

// Grupos para los que ya establecimos una "línea base" (ver más abajo). Se
// persiste junto con la sesión para no repetirla en cada reinicio.
const RUTA_BASELINE = path.join(path.resolve('./.wwebjs_auth/'), 'chats-con-baseline.json');

function cargarBaseline() {
  try {
    const data = JSON.parse(fs.readFileSync(RUTA_BASELINE, 'utf8'));
    return new Set(Array.isArray(data) ? data : []);
  } catch {
    return new Set();
  }
}

function guardarBaseline() {
  try {
    fs.mkdirSync(path.dirname(RUTA_BASELINE), { recursive: true });
    fs.writeFileSync(RUTA_BASELINE, JSON.stringify([...chatsConBaseline]));
  } catch (e) {
    console.warn('[Bot] No se pudo guardar chats-con-baseline.json:', e.message);
  }
}

let chatsConBaseline = cargarBaseline();

// Caché de "jid del grupo → nombre" para no depender de una llamada en vivo
// (getChat/getChats) por cada mensaje — ver resolverChat() más abajo. Se
// completa sola la primera vez que una resolución en vivo funciona (o queda
// vacía para siempre si GRUPOS_JIDS ya cubre todo).
const RUTA_GRUPOS = path.join(path.resolve('./.wwebjs_auth/'), 'grupos-conocidos.json');

function cargarGruposConocidos() {
  try {
    const data = JSON.parse(fs.readFileSync(RUTA_GRUPOS, 'utf8'));
    return new Map(Object.entries(data || {}));
  } catch {
    return new Map();
  }
}

function guardarGruposConocidos() {
  try {
    fs.mkdirSync(path.dirname(RUTA_GRUPOS), { recursive: true });
    fs.writeFileSync(RUTA_GRUPOS, JSON.stringify(Object.fromEntries(gruposConocidos)));
  } catch (e) {
    console.warn('[Bot] No se pudo guardar grupos-conocidos.json:', e.message);
  }
}

let gruposConocidos = cargarGruposConocidos();

// El stack de Puppeteer del bug de getChats()/getChatById() (ver resolverChat
// más abajo) es larguísimo y, mientras whatsapp-web.js no lo parchee, sale
// SIEMPRE que rompe — sin esto inundaba la consola con el mismo stack
// repetido en cada reconciliación y en cada mensaje de un grupo no resuelto.
// Lo mostramos completo una vez (para poder diagnosticarlo si hace falta) y
// después solo un aviso corto, por JID en el caso de resolverChat().
let getChatsStackYaMostrado = false;
const jidsSinResolverYaAvisados = new Set();

/**
 * Resuelve el chat de un mensaje SIN pasar por `msg.getChat()`/`client.getChatById()`
 * salvo que no quede otra — esas dos llamadas evalúan código dentro del contexto
 * de WhatsApp Web (Puppeteer) contra el Store interno, que rompe cada vez que
 * WhatsApp cambia algo ahí adentro (bug recurrente y conocido de whatsapp-web.js,
 * fuera de nuestro control — ver error "r: r" en los logs). Como esto pasa en
 * TODOS los mensajes, cuando se rompe el bot deja de procesar comprobantes por
 * completo. Acá evitamos la llamada en vivo siempre que se pueda:
 *   1) GRUPOS_JIDS (config manual) — si está seteado, cero llamadas en vivo.
 *   2) gruposConocidos (caché persistida) — de una resolución en vivo anterior.
 *   3) Como último recurso, sí llama a msg.getChat() — si falla, no tira el
 *      mensaje: lo deja sin marcar procesado para que la reconciliación
 *      periódica lo reintente más tarde (por si la falla es transitoria).
 */
async function resolverChat(msg) {
  const jid = msg.id.remote || msg.from;
  const esGrupo = jid.endsWith('@g.us');
  if (!esGrupo) return { id: { _serialized: jid }, name: null, isGroup: false };

  const nombreManual = GRUPOS_JIDS.get(jid);
  if (nombreManual) return { id: { _serialized: jid }, name: nombreManual, isGroup: true };

  const nombreCacheado = gruposConocidos.get(jid);
  if (nombreCacheado) return { id: { _serialized: jid }, name: nombreCacheado, isGroup: true };

  try {
    const chat = await msg.getChat();
    if (chat.name) {
      gruposConocidos.set(jid, chat.name);
      guardarGruposConocidos();
    }
    return chat;
  } catch (e) {
    const aviso = `[Bot] No se pudo resolver el grupo del mensaje (jid: ${jid}) — se reintenta en la próxima reconciliación. Si este jid corresponde a "Comprobantes Transferencias" o "Comprobantes Efectivo", agregalo a GRUPOS_JIDS en el .env para no depender más de esta llamada.`;
    if (jidsSinResolverYaAvisados.has(jid)) {
      console.warn(aviso);
    } else {
      jidsSinResolverYaAvisados.add(jid);
      console.warn(aviso + ' (stack completo, solo se muestra una vez por jid):', e.stack || e);
    }
    return null;
  }
}

// Estado del bot expuesto a la pantalla web "Estado del bot".
let estadoBot = {
  conectado: false,
  qrDataUrl: null,                         // QR como imagen (data URL) para re-vincular desde el navegador
  motivo: 'Iniciando...',
  ultimaActualizacion: new Date().toISOString(),
};
const BOT_HTTP_PORT = parseInt(process.env.BOT_HTTP_PORT || '3001', 10);

/**
 * Si el contenedor se reinició de forma no controlada (crash, OOM, `docker stop`
 * forzado), Chromium deja un archivo `SingletonLock` en el perfil persistido
 * (volumen `wpp_auth`). Al arrancar de nuevo, Chromium ve ese lock y se niega
 * a iniciar creyendo que "otra máquina" todavía lo tiene abierto — aunque en
 * realidad el proceso viejo ya no existe. Como solo puede haber una instancia
 * de este contenedor corriendo a la vez, cualquier lock que encontremos acá es
 * necesariamente viejo: lo borramos antes de que Chromium intente arrancar.
 */
function limpiarLocksDeSesionColgados() {
  // Misma resolución que usa LocalAuth internamente (relativa a process.cwd()),
  // sin clientId → carpeta 'session' a secas.
  const dirSesion = path.join(path.resolve('./.wwebjs_auth/'), 'session');
  const archivosLock = ['SingletonLock', 'SingletonCookie', 'SingletonSocket'];
  for (const nombre of archivosLock) {
    const ruta = path.join(dirSesion, nombre);
    try {
      fs.unlinkSync(ruta);
      console.log(`[Bot] Lock de sesión colgado eliminado: ${nombre}`);
    } catch (e) {
      if (e.code !== 'ENOENT') console.warn(`[Bot] No se pudo limpiar ${nombre}: ${e.message}`);
    }
  }
}
limpiarLocksDeSesionColgados();

// NO se fija la versión de WhatsApp Web: el bot carga la que WhatsApp sirve en
// vivo. Se probó pinnear una versión concreta (webVersion + webVersionCache
// local con el HTML congelado) para intentar arreglar el bug de lectura de
// comprobantes, y NO sirvió: el HTML pinneado es solo el "shell", el bundle JS
// pesado lo sigue bajando WhatsApp en vivo, así que el desajuste del Store
// (getChats/downloadMedia) pasa igual. Encima una versión pinneada es más
// frágil para conectar (WhatsApp puede dejar de aceptarla), así que dejar la
// versión viva es lo más confiable para que el bot AL MENOS conecte y registre.
//
// El problema de fondo de downloadMedia es de whatsapp-web.js (ya en su última
// versión, 1.34.7) contra el bundle actual de WhatsApp: window.require(
// 'WAWebCollections') no resuelve. No es arreglable desde acá; se maneja con el
// fallback de monto-en-el-pie (ver procesarPago).

// ─── Cliente de WhatsApp ─────────────────────────────────────────────────────
const client = new Client({
  authStrategy: new LocalAuth(),
  puppeteer: {
    headless: true,
    args: ['--no-sandbox', '--disable-setuid-sandbox'],
    // En Docker usamos el Chromium del sistema; en dev queda undefined (puppeteer usa el suyo).
    executablePath: process.env.PUPPETEER_EXECUTABLE_PATH || undefined,
  },
});

// Watchdog de arranque: a veces el cliente se cuelga en silencio entre
// 'authenticated' y 'ready' (sin tirar ningún error) — la inyección de
// WWebJS en la página de WhatsApp Web queda a medio terminar y ahí se
// queda para siempre. Sin esto, el bot quedaba "Iniciando..." de por vida
// sin ninguna forma de detectarlo ni recuperarse solo.
const READY_TIMEOUT_MS = 2 * 60 * 1000; // 2 minutos desde initialize()
let readyWatchdog = null;

function armarWatchdogReady() {
  if (readyWatchdog) clearTimeout(readyWatchdog);
  readyWatchdog = setTimeout(() => {
    console.error(`💥 El bot no llegó a "listo" en ${READY_TIMEOUT_MS / 1000}s desde que arrancó — quedó colgado a mitad de la conexión con WhatsApp Web. Reiniciando (lo levanta Docker con una sesión de Chromium limpia).`);
    process.exit(1);
  }, READY_TIMEOUT_MS);
}

function desarmarWatchdogReady() {
  if (readyWatchdog) { clearTimeout(readyWatchdog); readyWatchdog = null; }
}

client.on('qr', async (qr) => {
  console.log('\n┌──────────────────────────────────────────────────────┐');
  console.log('│  Escaneá el QR (terminal) o desde la pantalla web:   │');
  console.log('│  WhatsApp → Dispositivos vinculados → Vincular        │');
  console.log('└──────────────────────────────────────────────────────┘\n');
  qrcode.generate(qr, { small: true });
  // Lo exponemos también como imagen para la pantalla "Estado del bot"
  try { estadoBot.qrDataUrl = await QRCode.toDataURL(qr); } catch (e) { estadoBot.qrDataUrl = null; }
  estadoBot.conectado = false;
  estadoBot.motivo = 'Esperando vinculación — escaneá el QR';
  estadoBot.ultimaActualizacion = new Date().toISOString();
  // Esperar que alguien escanee el QR puede tardar lo que tarde — no es un
  // cuelgue, así que no cuenta contra el watchdog.
  desarmarWatchdogReady();
});

client.on('authenticated', () => {
  console.log('🔐 Autenticado — sesión guardada.');
  // De acá en más es todo automático (inyección de WWebJS + carga del
  // Store de WhatsApp Web) — si no llega a "listo" en el plazo, es que se
  // colgó de verdad.
  armarWatchdogReady();
});
client.on('auth_failure', (m) => {
  console.error('❌ Falló la autenticación:', m);
  estadoBot.conectado = false;
  estadoBot.motivo = 'Falló la autenticación';
  estadoBot.ultimaActualizacion = new Date().toISOString();
});
client.on('disconnected', async (r) => {
  console.warn('⚠️  Bot desconectado:', r, '— intentando reconectar...');
  estadoBot.conectado = false;
  estadoBot.qrDataUrl = null;
  estadoBot.motivo = 'Desconectado: ' + String(r);
  estadoBot.ultimaActualizacion = new Date().toISOString();
  // Reintenta: si se perdió la sesión, vuelve a disparar 'qr' (nuevo QR para la pantalla)
  armarWatchdogReady();
  try { await client.initialize(); } catch (e) { console.error('No se pudo reiniciar:', e.message); }
});

client.on('ready', async () => {
  desarmarWatchdogReady();
  estadoBot.conectado = true;
  estadoBot.qrDataUrl = null;
  estadoBot.motivo = GRUPOS.length
    ? `Conectado y escuchando ${GRUPOS.length} grupo(s).`
    : 'Conectado y escuchando todos los grupos.';
  estadoBot.ultimaActualizacion = new Date().toISOString();
  console.log('\n✅ Bot CONECTADO y escuchando.');
  console.log(GRUPOS.length ? `   Grupos: ${GRUPOS.join(' · ')}` : '   Escuchando TODOS los grupos.');
  console.log(`   Backend: ${BACKEND_ENABLED ? BACKEND_URL + ' (ACTIVO)' : 'desactivado (solo logueo)'}`);
  console.log(`   Lectura: ${GEMINI_ENABLED ? '✨ IA Gemini (cualquier billetera + fotos)' : 'reglas locales'}`);
  console.log('   Preparando OCR...');
  ocrWorker = await createWorker('spa');
  console.log('   ✅ OCR listo.\n');

  // Por si quedaron comprobantes sin procesar mientras el bot estuvo caído
  // (reinicio del contenedor, sesión desvinculada, corte de red, etc.)
  reconciliarChats(RECONCILIACION_LIMITE_RECONEXION).catch(e =>
    console.error('[Reconciliación] Error al arrancar:', e.message));
});

/**
 * Reprocesa los últimos mensajes de cada grupo monitoreado, por si alguno no
 * se llegó a procesar en vivo (bot desconectado, reinicio, etc.). Usa el mismo
 * camino que un mensaje en vivo (`manejarMensaje`), así que:
 *   - El anti-duplicado por N° de operación del backend filtra lo que ya está
 *     cargado (no hace falta comparar nada acá: si ya existe, el backend
 *     devuelve DUPLICADO y no se crea de nuevo).
 *   - `mensajesProcesados` evita repetir OCR/Gemini sobre mensajes que esta
 *     misma reconciliación (o una anterior) ya miró, aunque no hayan generado
 *     una carga (ej: no era comprobante).
 */
async function reconciliarChats(limitePorGrupo) {
  if (!estadoBot.conectado) return { chats: 0, mensajes: 0 };
  console.log(`\n🔄 Reconciliación: revisando historial (últimos ${limitePorGrupo} msj. por grupo)...`);
  let chatsRevisados = 0;
  let mensajesRevisados = 0;
  try {
    let chats;
    try {
      chats = await client.getChats();
    } catch (e) {
      // Bug conocido y recurrente de whatsapp-web.js: cada vez que WhatsApp
      // actualiza su versión web, client.getChats() puede tirar un error
      // interno (evalúa código minificado del lado del navegador) hasta que
      // la librería lo parchea — no es algo que podamos arreglar acá.
      // Fallback: en vez de listar TODOS los chats, pedimos uno por uno los
      // grupos que ya conocemos de reconciliaciones anteriores (persistidos
      // en chatsConBaseline) — es una llamada más chica y no siempre falla
      // aunque getChats() sí.
      if (getChatsStackYaMostrado) {
        console.warn('[Reconciliación] getChats() volvió a fallar (mismo bug ya reportado) — reintentando por ID con los grupos ya conocidos.');
      } else {
        getChatsStackYaMostrado = true;
        console.warn('[Reconciliación] getChats() falló (stack completo, solo se muestra una vez por sesión) — reintentando por ID con los grupos ya conocidos:', e.stack || e);
      }
      e._yaLogueado = true;
      const idsConocidos = [...chatsConBaseline];
      const resultados = await Promise.all(
        idsConocidos.map(id => client.getChatById(id).catch(() => null))
      );
      chats = resultados.filter(Boolean);
      if (chats.length === 0) throw e; // sin fallback posible (ningún grupo conocido todavía) → error original
    }
    const grupos = chats.filter(c => c.isGroup &&
      (GRUPOS.length === 0 || GRUPOS.includes(c.name.toLowerCase())));

    // Aprovechamos que acá SÍ tenemos el nombre en vivo para completar el
    // caché que usa resolverChat() en cada mensaje — así, aunque getChats()
    // vuelva a romperse después, los mensajes de estos grupos ya no dependen
    // de una llamada en vivo.
    let huboNombreNuevo = false;
    for (const chat of grupos) {
      const jid = chat.id._serialized;
      if (chat.name && gruposConocidos.get(jid) !== chat.name) {
        gruposConocidos.set(jid, chat.name);
        huboNombreNuevo = true;
      }
    }
    if (huboNombreNuevo) guardarGruposConocidos();

    for (const chat of grupos) {
      try {
        const idChat = chat.id._serialized;

        // Primera vez que vemos ESTE grupo (recién agregado a GRUPOS, o un grupo
        // de prueba renombrado para que coincida): no reprocesamos su historial
        // viejo — solo lo marcamos como visto, sin contestar ni registrar nada.
        // Si no hiciéramos esto, cualquier grupo con historial (por ejemplo uno
        // de pruebas reciclado) generaría una respuesta del bot por cada mensaje
        // viejo que tenga, todas de una vez ("bombardeo").
        if (!chatsConBaseline.has(idChat)) {
          const mensajes = await chat.fetchMessages({ limit: limitePorGrupo });
          for (const msg of mensajes) marcarProcesado(msg.id._serialized);
          chatsConBaseline.add(idChat);
          guardarBaseline();
          console.log(`   ℹ "${chat.name}" es nuevo para la reconciliación — línea base establecida (${mensajes.length} mensaje(s) ya existentes, sin reprocesar). De acá en más sí se van a recuperar los que falten.`);
          chatsRevisados++;
          continue;
        }

        const mensajes = await chat.fetchMessages({ limit: limitePorGrupo });
        mensajes.sort((a, b) => a.timestamp - b.timestamp); // más viejo primero, igual que en vivo
        for (const msg of mensajes) {
          await manejarMensaje(msg, { reconciliacion: true });
          mensajesRevisados++;
        }
        chatsRevisados++;
      } catch (e) {
        console.warn(`[Reconciliación] Error en el grupo "${chat.name}":`, e && e.stack || e);
      }
    }
  } catch (e) {
    // e.message a veces viene truncado/vacío en errores que vienen de adentro
    // del contexto de Puppeteer (whatsapp-web.js) — logueamos el objeto entero
    // para poder diagnosticar la próxima vez que pase (pasa siempre al conectar).
    // Si ya se logueó el stack completo más arriba (getChats() sin fallback
    // posible), no lo repetimos acá — es el mismo error re-lanzado.
    if (e && e._yaLogueado) {
      console.error('[Reconciliación] Error general: getChats() falló y no hay grupos conocidos como fallback (ver detalle arriba).');
    } else {
      console.error('[Reconciliación] Error general:', e && e.stack || e);
    }
  }
  console.log(`🔄 Reconciliación completa: ${chatsRevisados} grupo(s), ${mensajesRevisados} mensaje(s) revisado(s).\n`);
  return { chats: chatsRevisados, mensajes: mensajesRevisados };
}

// Ventanas de revisión: grande al reconectar/manual (probablemente hay más para
// recuperar), chica en el chequeo periódico (solo red de seguridad).
const RECONCILIACION_LIMITE_RECONEXION = parseInt(process.env.RECONCILIACION_LIMITE_RECONEXION || '100', 10);
const RECONCILIACION_LIMITE_MANUAL     = parseInt(process.env.RECONCILIACION_LIMITE_MANUAL || '150', 10);
const RECONCILIACION_LIMITE_PERIODICA  = parseInt(process.env.RECONCILIACION_LIMITE_PERIODICA || '25', 10);
const RECONCILIACION_INTERVALO_MIN     = parseInt(process.env.RECONCILIACION_INTERVALO_MIN || '30', 10);

// Chequeo periódico liviano: red de seguridad para el caso raro de un mensaje
// que se pierde sin que el bot llegue a marcar una desconexión real.
setInterval(() => {
  reconciliarChats(RECONCILIACION_LIMITE_PERIODICA).catch(e =>
    console.error('[Reconciliación] Error en chequeo periódico:', e.message));
}, RECONCILIACION_INTERVALO_MIN * 60 * 1000);

// ─── Manejo de mensajes ──────────────────────────────────────────────────────
// `manejarMensaje` es el mismo código para un mensaje que llega en vivo y para
// uno que se revisa después (reconciliación tras una desconexión) — así la
// lógica de emparejar comprobante+pie es idéntica en los dos casos y no hay
// que mantenerla dos veces. La única diferencia es `opciones.reconciliacion`.
async function manejarMensaje(msg, opciones = {}) {
  const { reconciliacion = false } = opciones;
  try {
    const chat = await resolverChat(msg);
    if (!chat) return; // no se pudo resolver el grupo — se reintenta solo en la próxima reconciliación
    if (!chat.isGroup) return;
    if (GRUPOS.length && !GRUPOS.includes(chat.name.toLowerCase())) return;

    // En reconciliación, si ya vimos este mensaje en una pasada anterior, no
    // repetimos OCR/Gemini ni volvemos a registrarlo (evita duplicados y gasto).
    if (reconciliacion && mensajesProcesados.has(msg.id._serialized)) return;

    const contacto = await msg.getContact();
    const clave = `${chat.id._serialized}|${contacto.number}`;

    if (msg.hasMedia) {
      // ── Llegó un comprobante ──
      const lectura = await leerComprobante(msg);
      const pie = parsearPie(msg.body || '');

      if (pie.receptor) {
        // Comprobante + pie en el mismo mensaje → procesar directo
        await procesarPago(msg, chat, contacto, pie, lectura, undefined, { reconciliacion });
        if (reconciliacion) marcarProcesado(msg.id._serialized);
        return;
      }

      // Sin pie en la propia foto: ¿ya nos habían mandado el "Emisor (Receptor)"
      // antes (WhatsApp separó una foto+texto mandados juntos, o lo escribieron
      // primero)? Si sí, se empareja directo sin pedirlo de nuevo.
      const pendPie = pendientesPie.get(clave);
      if (pendPie && (Date.now() - pendPie.ts) < TIMEOUT_PENDIENTE) {
        pendientesPie.delete(clave);
        await procesarPago(msg, chat, contacto, pendPie.pie, lectura, pendPie.msgPie, { reconciliacion });
        if (reconciliacion) {
          marcarProcesado(msg.id._serialized);
          marcarProcesado(pendPie.msgPie.id._serialized);
        }
        return;
      }

      // Ninguno de los dos casos → guardar y esperar el siguiente mensaje
      pendientes.set(clave, { msg, chat, contacto, lectura, ts: Date.now() });
      if (!reconciliacion) {
        console.log(`\n📎 [${chat.name}] Comprobante de ${contacto.pushname || contacto.number} — esperando "Emisor (Receptor)"...`);
        await msg.reply('📎 Recibí el comprobante. Ahora mandá quién a quién: *Emisor (Receptor)*\nEj: Dr. García (Carlos López)');
      }
      // En reconciliación no lo marcamos procesado todavía: si el pie viene
      // en un mensaje posterior DENTRO del mismo lote revisado, se empareja
      // más abajo igual que en vivo.

    } else if (msg.body) {
      // ── Grupo de efectivo: "Emisor (Receptor) monto" alcanza directo — no
      // hace falta la palabra "efectivo" (ya lo dice el grupo) ni foto. ──
      if (GRUPOS_EFECTIVO.includes(chat.name.toLowerCase())) {
        const pieEfectivo = parsearPie(msg.body);
        if (pieEfectivo.receptor && pieEfectivo.montoManual) {
          await registrarEfectivo(msg, chat, contacto,
            { monto: pieEfectivo.montoManual, receptor: pieEfectivo.receptor, emisor: pieEfectivo.emisor },
            { reconciliacion });
          if (reconciliacion) marcarProcesado(msg.id._serialized);
          return;
        }
        if (pieEfectivo.receptor && !pieEfectivo.montoManual) {
          if (!reconciliacion) {
            await msg.reply('💵 Anotado el receptor, pero me falta el monto. Mandá: *Emisor (Receptor) monto*\nEj: Dr. García (Proveedor X) 85000');
          }
          if (reconciliacion) marcarProcesado(msg.id._serialized);
          return;
        }
      }

      // ── ¿Es declaración de efectivo con la palabra clave? (formato viejo,
      // sigue andando en cualquier grupo por compatibilidad) ──
      const efectivo = parsearEfectivo(msg.body);
      if (efectivo) {
        await registrarEfectivo(msg, chat, contacto, { ...efectivo, emisor: null }, { reconciliacion });
        if (reconciliacion) marcarProcesado(msg.id._serialized);
        return;
      }

      // ── Llegó texto: ¿es el pie de un comprobante pendiente? ──
      const pie = parsearPie(msg.body);
      if (!pie.receptor) {
        if (reconciliacion) marcarProcesado(msg.id._serialized);
        return;
      }

      const pend = pendientes.get(clave);
      if (pend && (Date.now() - pend.ts) < TIMEOUT_PENDIENTE) {
        // Ya teníamos el comprobante esperando este pie → emparejar
        pendientes.delete(clave);
        await procesarPago(pend.msg, pend.chat, pend.contacto, pie, pend.lectura, msg, { reconciliacion });
        if (reconciliacion) {
          marcarProcesado(pend.msg.id._serialized);
          marcarProcesado(msg.id._serialized);
        }
      } else {
        // El pie llegó primero (sin comprobante todavía) → lo guardamos para
        // cuando llegue la foto, en vez de perderlo en silencio.
        pendientesPie.set(clave, { pie, msgPie: msg, ts: Date.now() });
        if (!reconciliacion) {
          console.log(`\n📝 [${chat.name}] "Emisor (Receptor)" de ${contacto.pushname || contacto.number} — esperando el comprobante...`);
          await msg.reply('📝 Anotado. Mandame ahora la foto o el PDF del comprobante.');
        }
        if (reconciliacion) marcarProcesado(msg.id._serialized);
      }
    }
  } catch (err) {
    // .message a veces viene truncado en errores que salen de adentro del
    // contexto de Puppeteer (evaluate sobre WhatsApp Web) — logueamos el
    // stack completo para poder diagnosticar de verdad qué pasó.
    console.error('❌ Error procesando mensaje:', err && err.stack || err);
  }
}

client.on('message', (msg) => manejarMensaje(msg).catch(err =>
  console.error('❌ Error no capturado en manejarMensaje:', err && err.stack || err)));

// ─── Procesar un pago (comprobante + pie ya emparejados) ─────────────────────
async function procesarPago(msgComprobante, chat, contacto, pie, lectura, msgPie, opciones = {}) {
  const { reconciliacion = false } = opciones;
  const cargadoPorNombre   = contacto.pushname || contacto.name || 'Desconocido';
  const cargadoPorTelefono = contacto.number;
  const prefijoReconciliacion = reconciliacion ? '🔄 _(recuperado tras una desconexión)_\n' : '';
  const responder = (txt) => (msgPie || msgComprobante).reply(prefijoReconciliacion + txt);

  // Monto: el del comprobante (PDF) o, si no se leyó, el que pusieron en el pie
  const monto = lectura.monto ?? pie.montoManual;
  const origenMonto = lectura.monto ? `comprobante (${lectura.confianza})` : (pie.montoManual ? 'pie del mensaje' : 'no hay');
  const confianzaMonto = lectura.monto ? lectura.confianza : (pie.montoManual ? 'manual' : 'baja');

  console.log('\n📩 ─────────── COMPROBANTE A PROCESAR ───────────');
  console.log(`   Grupo:        ${chat.name}`);
  console.log(`   Cargado por:  ${cargadoPorNombre} (${cargadoPorTelefono})`);
  console.log(`   Pie:          Emisor="${pie.emisor}"  Receptor="${pie.receptor}"`);
  console.log(`   Monto:        ${monto ?? '⚠ no detectado'} (origen: ${origenMonto})`);
  console.log(`   N° operación: ${lectura.idOperacion ?? '⚠ no detectado'}`);
  console.log(`   Comprobante:  De="${lectura.de ?? '?'}"  Para="${lectura.para ?? '?'}"`);

  if (!monto) {
    console.log('   ⚠ No pude leer el monto (ni del comprobante ni del pie).');
    await responder(
      `⚠️ Recibí el comprobante para *${pie.receptor}* pero no pude leer el monto de la imagen.\n` +
      `Mandá el pie *con el monto al final* y queda registrado igual:\n` +
      `*${pie.emisor || 'Emisor'} (${pie.receptor}) 10000*\n\n` +
      `_Tip: podés escribir eso mismo como epígrafe de la foto y se registra en un solo paso._`
    );
    return;
  }

  // ── Validación cruzada: pie vs De/Para del comprobante ──
  const validacion = validarPersonas(pie, lectura);
  console.log(`   Validación:   ${validacion.ok ? '✅ coincide' : '⚠ NO coincide'} (${validacion.detalle})`);

  const montoFmt = monto.toLocaleString('es-AR');

  if (!BACKEND_ENABLED) {
    console.log('   ℹ Backend desactivado — solo logueo.');
    console.log('   ───────────────────────────────────────────────\n');
    await responder(
      `🧪 *Modo prueba* — esto detecté:\n` +
      `• Monto: *$${montoFmt}*\n` +
      `• Pagó: ${pie.emisor || '(sin emisor)'}\n` +
      `• Recibió: *${pie.receptor}*\n` +
      `• N° operación: ${lectura.idOperacion || '—'}\n` +
      (validacion.ok ? `✅ Los datos coinciden con el comprobante.` : `⚠️ *No coincide:* ${validacion.detalle}`)
    );
    return;
  }

  // ── Registrar en el backend ──
  let resultado;
  try {
    resultado = await registrarPago({
      monto,
      emisor: pie.emisor,
      receptorNombre: pie.receptor,
      idOperacion: lectura.idOperacion,
      cargadoPorNombre, cargadoPorTelefono,
      grupoOrigen: chat.name,
      // archivo del comprobante para guardar en MinIO
      comprobanteBase64: lectura.comprobanteBase64,
      comprobanteMime: lectura.comprobanteMime,
      comprobanteNombre: lectura.comprobanteNombre,
    });
  } catch (e) {
    const msgErr = e.response?.data?.mensaje || e.message;
    console.log('   ❌ Error al conectar con el backend:', msgErr);
    await responder(`⚠️ No se pudo registrar (error de conexión con el sistema): ${msgErr}`);
    return;
  }

  // El backend SIEMPRE responde 200 con el estado del registro
  const estado = resultado?.estado;
  console.log(`   Resultado:    ${estado} — ${resultado?.mensaje || ''}`);
  console.log('   ───────────────────────────────────────────────\n');

  if (estado === 'DUPLICADO') {
    await responder(`ℹ️ Ese comprobante ya estaba registrado (operación ${lectura.idOperacion || '—'}).`);
    return;
  }
  if (estado !== 'REGISTRADO') {
    await responder(`⚠️ No se registró: ${resultado?.mensaje || 'el receptor no es un empleado ni un proveedor conocido.'}`);
    return;
  }

  // Registrado OK — distinguimos sueldo vs pago a proveedor
  const tipoTxt = resultado.tipoReceptor === 'PROVEEDOR' ? 'Pago a proveedor' : 'Sueldo';
  let aviso = '';
  if (confianzaMonto === 'baja') aviso += `\n⚠️ No estoy seguro del monto, *verificalo*.`;
  if (!validacion.ok)            aviso += `\n⚠️ El comprobante no coincide del todo con "${pie.receptor}", *revisalo*.`;

  await responder(
    `✅ *Cargado al sistema correctamente* (${tipoTxt})\n` +
    `• Monto: $${montoFmt}\n` +
    `• Pagó: ${pie.emisor || '—'}\n` +
    `• Recibió: ${resultado.receptorResuelto || pie.receptor}\n` +
    `• N° operación: ${lectura.idOperacion || '—'}` +
    aviso
  );
}

// ─── Lectura del comprobante (PDF o imagen) ──────────────────────────────────
/**
 * Descarga el adjunto SIN buscar el mensaje en la base local de WhatsApp Web.
 *
 * Por qué existe esto: el downloadMedia() de whatsapp-web.js arranca buscando el
 * modelo del mensaje con Msg.get(id) / Msg.getMessagesById([id]), y HOY ese paso
 * revienta contra IndexedDB:
 *
 *     DataError: Failed to execute 'get' on 'IDBObjectStore':
 *                No key or key range specified
 *
 * Como la librería aborta ahí, nunca llega a descargar nada — y el error sale
 * minificado como "r", que no dice absolutamente nada.
 *
 * La clave: NO hace falta buscar el mensaje. Todos los datos necesarios para
 * bajar y desencriptar el archivo (directPath, mediaKey, filehash...) ya viajan
 * en el objeto del mensaje del lado de Node (msg._data), porque WhatsApp los
 * mandó junto con la notificación del mensaje. Así que se los pasamos a la
 * página ya resueltos y llamamos derecho al downloadManager, salteando por
 * completo el paso roto.
 *
 * Devuelve { data, mimetype, filename, filesize } igual que la librería, o
 * { _fallo: true, diag } con el detalle de dónde y por qué falló.
 */
async function descargarMediaDirecto(msg) {
  // Metadatos del media tal como los mandó WhatsApp, tomados del lado de Node.
  const d = msg._data || {};
  const meta = {
    directPath:        d.directPath        ?? msg.directPath,
    encFilehash:       d.encFilehash       ?? msg.encFilehash,
    filehash:          d.filehash          ?? msg.filehash,
    mediaKey:          d.mediaKey          ?? msg.mediaKey,
    mediaKeyTimestamp: d.mediaKeyTimestamp ?? msg.mediaKeyTimestamp,
    type:              d.type              ?? msg.type,
    mimetype:          d.mimetype          ?? msg.mimetype ?? '',
    filename:          d.filename          ?? msg.filename ?? null,
    size:              d.size              ?? msg.size     ?? null,
  };

  if (!meta.directPath || !meta.mediaKey) {
    return { _fallo: true, diag: { paso: 'metadatos', error: 'el mensaje no trae directPath/mediaKey' } };
  }

  return await client.pupPage.evaluate(async (m) => {
    const diag = { paso: 'inicio', tipo: m.type };
    try {
      diag.paso = 'download-manager';
      let DM = null;
      try { DM = window.require('WAWebDownloadManager'); } catch (e) { diag.errModulo = String(e); }
      const downloadManager = DM && DM.downloadManager;
      if (!downloadManager || typeof downloadManager.downloadAndMaybeDecrypt !== 'function') {
        diag.error = 'WAWebDownloadManager no disponible';
        return { _fallo: true, diag };
      }

      // Bajar + desencriptar directo con los metadatos que ya teníamos.
      diag.paso = 'descargar-desencriptar';
      const mockQpl = { addAnnotations() { return this; }, addPoint() { return this; } };
      const buffer = await downloadManager.downloadAndMaybeDecrypt({
        directPath: m.directPath,
        encFilehash: m.encFilehash,
        filehash: m.filehash,
        mediaKey: m.mediaKey,
        mediaKeyTimestamp: m.mediaKeyTimestamp,
        type: m.type,
        signal: new AbortController().signal,
        downloadQpl: mockQpl,
      });

      diag.paso = 'base64';
      const data = await window.WWebJS.arrayBufferToBase64Async(buffer);
      // mimetype siempre string: leerComprobante hace .startsWith() sobre esto.
      return { data, mimetype: m.mimetype || '', filename: m.filename, filesize: m.size };
    } catch (e) {
      diag.error = String((e && (e.message || e.name)) || e);
      if (e && e.stack) diag.stack = String(e.stack).slice(0, 300);
      return { _fallo: true, diag };
    }
  }, meta);
}

/**
 * Descarga el comprobante: primero por la vía normal de la librería (si anda,
 * anda) y, si falla, por la vía directa de arriba, que sortea el paso que hoy
 * está roto. Solo se rinde si las dos fallan.
 */
async function descargarMediaConReintentos(msg, intentos = 3) {
  for (let i = 1; i <= intentos; i++) {
    try {
      return await msg.downloadMedia();
    } catch (e) {
      // Vía directa: es la que salva el caso del paso 2 roto.
      try {
        const r = await descargarMediaDirecto(msg);
        if (r && !r._fallo && r.data) {
          console.log('   (descarga directa OK — se sorteó el bug de la librería)');
          return r;
        }
        if (r && r._fallo) {
          console.log(`   (descarga directa falló en "${r.diag.paso}": ${r.diag.error || 's/d'}` +
            `${r.diag.mediaStage ? ` | mediaStage=${r.diag.mediaStage}` : ''}` +
            `${r.diag.avisoResolver ? ` | resolver: ${r.diag.avisoResolver}` : ''})`);
        }
      } catch (e2) {
        console.log('   (descarga directa lanzó:', e2 && e2.message, ')');
      }
      if (i === intentos) throw e;
      console.log(`   (downloadMedia falló, reintento ${i}/${intentos - 1}...)`);
      await new Promise(r => setTimeout(r, 1500));
    }
  }
}

async function leerComprobante(msg) {
  const vacio = { monto: null, confianza: 'baja', idOperacion: null, de: null, para: null, texto: '' };
  try {
    const media = await descargarMediaConReintentos(msg);
    if (!media || !media.data) return vacio;
    const buffer = Buffer.from(media.data, 'base64');
    const esPdf = media.mimetype === 'application/pdf';
    const esImg = media.mimetype.startsWith('image/');
    if (!esPdf && !esImg) return vacio;

    // Archivo para guardar en MinIO (lo manda el bot al backend)
    const archivo = {
      comprobanteBase64: media.data,
      comprobanteMime: media.mimetype,
      comprobanteNombre: media.filename || ('comprobante' + (esPdf ? '.pdf' : '.jpg')),
    };

    // Texto del PDF (para reglas y para Gemini-texto)
    let texto = '';
    if (esPdf) {
      texto = (await pdfParse(buffer)).text || '';
      console.log('   (PDF — texto extraído)');
    }

    // 1) Reglas locales primero (gratis, sin gastar cuota de Gemini — resuelven
    // bien MP/Personal Pay, que son los que llegan en la práctica)
    if (esImg && !texto && ocrWorker) {
      texto = (await ocrWorker.recognize(buffer)).data.text || '';
      console.log('   (imagen — OCR local)');
    }
    logTexto(texto);
    const { monto, confianza } = extraerMonto(texto);
    const local = {
      monto,
      confianza,
      idOperacion: extraerIdOperacion(texto),
      ...extraerDePara(texto),
    };

    // 2) IA Gemini solo como respaldo — cuando las reglas locales no encontraron
    // un monto confiable (billetera rara, foto mala, etc). Cuota muy limitada,
    // compartida con el mail-scraper: se reserva para lo que de verdad la necesita.
    if (local.confianza !== 'alta' && GEMINI_ENABLED && geminiModel) {
      const g = await leerConGemini(media, texto);
      if (g && g.monto) {
        console.log('   ✨ Leído con IA (Gemini)');
        return { monto: g.monto, confianza: 'alta', idOperacion: g.idOperacion, de: g.de, para: g.para, texto, ...archivo };
      }
    }

    return { ...local, texto, ...archivo };
  } catch (e) {
    console.log('   (error leyendo comprobante:', e.message, ')');
    return vacio;
  }
}

function logTexto(texto) {
  if (!texto) return;
  console.log('   ┌─ Texto del comprobante ─────────');
  texto.split('\n').filter(l => l.trim()).slice(0, 25).forEach(l => console.log('   │ ' + l.trim()));
  console.log('   └─────────────────────────────────');
}

/**
 * Lee el comprobante con IA (Gemini). Funciona con cualquier billetera y con
 * fotos (visión). Devuelve { monto, de, para, idOperacion } o null si falla.
 */
async function leerConGemini(media, textoPdf) {
  if (!geminiModel) return null;
  try {
    const instruccion =
      'Sos un extractor de datos de comprobantes de transferencia argentinos ' +
      '(Mercado Pago, Personal Pay, Ualá, Naranja X, MODO, bancos, etc). ' +
      'Devolvé SOLO un JSON válido, sin markdown ni texto extra, con esta forma exacta:\n' +
      '{"monto": <entero en pesos sin decimales ni puntos, o null>, ' +
      '"emisor": <nombre de quien ENVIÓ el dinero (origen/de), o null>, ' +
      '"receptor": <nombre de quien RECIBIÓ el dinero (destino/para), o null>, ' +
      '"idOperacion": <número o código de operación, o null>}\n' +
      'Reglas: el monto es la cantidad transferida (ej 60000), no el CBU ni el nro de cuenta. ' +
      'Si un dato no figura, poné null. No inventes nada.';

    // Tanto imagen como PDF se mandan como archivo directo: Gemini lee el
    // original (con acentos correctos), mucho mejor que el texto extraído por
    // pdf-parse, que rompe los acentos ("Nicolás" → "Nicol S").
    const result = await geminiModel.generateContent([
      instruccion,
      { inlineData: { mimeType: media.mimetype, data: media.data } },
    ]);

    let txt = result.response.text().trim()
      .replace(/^```json\s*/i, '').replace(/^```\s*/, '').replace(/```\s*$/, '').trim();
    const d = JSON.parse(txt);
    return {
      monto: d.monto ? Math.round(Number(d.monto)) : null,
      de: d.emisor || null,
      para: d.receptor || null,
      idOperacion: d.idOperacion ? String(d.idOperacion) : null,
    };
  } catch (e) {
    console.log('   (Gemini falló:', e.message, '— uso reglas)');
    return null;
  }
}

// ─── Parsers ─────────────────────────────────────────────────────────────────

/**
 * Detecta una declaración de efectivo en el grupo.
 * Formato: "efectivo 50000 (Receptor)" o "efectivo $50.000 (Receptor)"
 * Devuelve { monto, receptor } o null si no coincide.
 */
function parsearEfectivo(texto) {
  if (!texto) return null;
  const m = texto.trim().match(/^efectivo\s+\$?\s*([\d.,]+)\s*\(([^)]+)\)/i);
  if (!m) return null;
  const montoStr = m[1].replace(/\./g, '').replace(',', '.');
  const monto = parseFloat(montoStr);
  if (!monto || monto < 100) return null;
  return { monto: Math.round(monto), receptor: m[2].trim() };
}

/**
 * Pie: "EMISOR (RECEPTOR)" → { emisor, receptor, montoManual }.
 * El monto es OPCIONAL: si lo ponés (antes o después del paréntesis), se usa
 * como respaldo cuando el comprobante es una foto que el OCR no pudo leer.
 *   "Dr García (Carlos López)"        → sin monto (se lee del comprobante)
 *   "Dr García (Carlos López) 10000"  → con monto manual
 */
function parsearPie(texto) {
  const r = { emisor: null, receptor: null, montoManual: null };
  if (!texto) return r;
  const m = texto.match(/^(.*?)\(([^)]+)\)(.*)$/s);
  if (!m) return r;
  r.emisor = m[1].trim() || null;
  r.receptor = m[2].trim();

  // Monto opcional: buscar un número en lo que rodea al paréntesis
  const resto = `${m[1]} ${m[3]}`;
  const mm = resto.match(/\$?\s*(\d{1,3}(?:\.\d{3})+(?:,\d{2})?|\d{3,}(?:,\d{2})?)/);
  if (mm) {
    const v = Math.round(parseFloat(mm[1].replace(/\./g, '').replace(',', '.')));
    if (v >= 100) r.montoManual = v;
  }
  return r;
}

/**
 * Monto: solo números con formato de dinero ($60.000 / $60.000,00). Descarta IDs.
 * Maneja el caso de Personal Pay donde el monto viene partido en líneas
 * ("$" / "60.000" / "00") uniendo el texto antes de buscar.
 */
function extraerMonto(texto) {
  const claves = /(monto|importe|total|transferiste|enviaste|enviaron|pagaste|recibiste|enviado|recibido)/i;

  // Unimos líneas para tolerar montos partidos: "$\n60.000\n00" → "$ 60.000 00"
  // y también el caso normal. Trabajamos sobre el texto "aplanado" por bloques.
  const lineas = texto.split('\n');
  const candidatos = [];

  for (let i = 0; i < lineas.length; i++) {
    // Bloque: la línea + las 2 siguientes (por si el monto está partido)
    const bloque = (lineas[i] + ' ' + (lineas[i + 1] || '') + ' ' + (lineas[i + 2] || '')).trim();
    const enClave = claves.test(lineas[i]) || claves.test(lineas[Math.max(0, i - 1)] || '');

    // $ opcional, miles obligatorio (60.000), centavos opcionales separados o con coma
    const regex = /(\$\s*)?(\d{1,3}(?:\.\d{3})+)(?:[,\s](\d{2})\b)?/g;
    let m;
    while ((m = regex.exec(bloque)) !== null) {
      const simbolo = !!m[1];
      const entero = parseInt(m[2].replace(/\./g, ''), 10);
      if (entero < 100 || entero > 99_000_000) continue;
      candidatos.push({ valor: entero, simbolo, enClave });
    }
  }

  if (!candidatos.length) return { monto: null, confianza: 'baja' };

  // Dedup por valor (el bloque solapa líneas y puede repetir)
  const unicos = [];
  const vistos = new Set();
  for (const c of candidatos) {
    const k = c.valor + (c.simbolo ? 'S' : '') + (c.enClave ? 'C' : '');
    if (!vistos.has(k)) { vistos.add(k); unicos.push(c); }
  }

  unicos.sort((a, b) =>
    (a.simbolo !== b.simbolo) ? (a.simbolo ? -1 : 1)
    : (a.enClave !== b.enClave) ? (a.enClave ? -1 : 1)
    : (b.valor - a.valor));
  const mejor = unicos[0];
  return { monto: mejor.valor, confianza: (mejor.simbolo || mejor.enClave) ? 'alta' : 'media' };
}

/** N° de operación / código del comprobante. */
function extraerIdOperacion(texto) {
  const patrones = [
    /n[uú]mero\s+de\s+operaci[oó]n[^\d]*([0-9]{6,})/i,
    /(?:n[º°o]?\.?\s*de\s*)?operaci[oó]n[:\s#nro.]*([A-Z0-9-]{6,})/i,
    /c[oó]digo\s+de\s+identificaci[oó]n[:\s]*([A-Z0-9-]{6,})/i,
    /(?:n[º°o]?\.?\s*)?transacci[oó]n[:\s#nro.]*([A-Z0-9-]{6,})/i,
  ];
  for (const p of patrones) {
    const m = texto.match(p);
    if (m) return m[1].trim();
  }
  return null;
}

/**
 * Extrae emisor y receptor del comprobante. Soporta muchas billeteras
 * reconociendo varias etiquetas, en tres formas:
 *   - etiqueta sola, nombre en la línea siguiente  (Mercado Pago: "De"\n"Nombre")
 *   - etiqueta + nombre en la misma línea          ("Origen: Juan")
 *   - etiqueta pegada al nombre                     (Personal Pay: "OrigenJuan")
 */
const ETIQ_EMISOR   = ['de', 'origen', 'remitente', 'ordenante', 'enviado por', 'titular origen'];
const ETIQ_RECEPTOR = ['para', 'destino', 'destinatario', 'beneficiario', 'enviado a', 'acreditado en', 'titular destino'];

function extraerDePara(texto) {
  const lineas = texto.split('\n').map(l => l.trim()).filter(Boolean);
  let de = null, para = null;
  for (let i = 0; i < lineas.length; i++) {
    if (!de)   { const v = matchEtiqueta(lineas[i], lineas[i + 1], ETIQ_EMISOR);   if (v) de = v; }
    if (!para) { const v = matchEtiqueta(lineas[i], lineas[i + 1], ETIQ_RECEPTOR); if (v) para = v; }
  }
  return { de, para };
}

/** Intenta extraer el nombre que sigue a alguna de las etiquetas dadas. */
function matchEtiqueta(linea, siguiente, etiquetas) {
  const low = linea.toLowerCase();
  for (const e of etiquetas) {
    // etiqueta sola en su línea → nombre en la siguiente (MP)
    if (low === e || low === e + ':') return (siguiente || '').trim() || null;
    // etiqueta + nombre en la misma línea, con separador
    if (low.startsWith(e + ' ') || low.startsWith(e + ':')) {
      return linea.slice(e.length).replace(/^[:\s]+/, '').trim() || null;
    }
    // etiqueta pegada al nombre (Personal Pay), solo etiquetas de una palabra,
    // y validando que lo que sigue empiece con mayúscula (un nombre real)
    if (!e.includes(' ') && low.startsWith(e) && linea.length > e.length) {
      const resto = linea.slice(e.length);
      if (/^[A-ZÁÉÍÓÚÑ]/.test(resto)) return resto.trim();
    }
  }
  return null;
}

/**
 * Compara dos nombres de forma flexible. Tolera:
 *  - acentos (normaliza)
 *  - palabras partidas por mala extracción de PDF ("Nicolás" → "Nicol S")
 *  - prefijos ("nicol" ↔ "nicolas")
 *  - títulos (Dr, Dra) que se ignoran
 *
 * Coincide si comparten una palabra (exacta o por prefijo de 4+ letras), o si
 * los nombres completos son muy similares (tolerancia a 1-2 caracteres).
 */
function nombresCoinciden(a, b) {
  if (!a || !b) return { ok: false, comun: null };
  const norm = s => s.toLowerCase()
    .normalize('NFD').replace(/[̀-ͯ]/g, '')          // quitar acentos
    .replace(/[^a-z\s]/g, ' ').replace(/\s+/g, ' ').trim();
  const TITULOS = new Set(['dr', 'dra', 'dro', 'sr', 'sra', 'lic']);

  const na = norm(a), nb = norm(b);
  const pa = na.split(' ').filter(w => w.length >= 3 && !TITULOS.has(w));
  const pb = nb.split(' ').filter(w => w.length >= 3 && !TITULOS.has(w));

  // 1) Palabra exacta o por prefijo (4+ letras) → tolera "nicol" vs "nicolas"
  for (const wa of pa) {
    for (const wb of pb) {
      if (wa === wb) return { ok: true, comun: wa };
      if (wa.length >= 4 && wb.length >= 4 && (wa.startsWith(wb) || wb.startsWith(wa))) {
        return { ok: true, comun: wa.length <= wb.length ? wa : wb };
      }
    }
  }

  // 2) Nombres completos sin espacios, muy similares (Levenshtein ≤ 20%)
  //    Cubre "nicolas" vs "nicol s" → "nicolas" vs "nicols" (distancia 1)
  const ca = na.replace(/\s/g, ''), cb = nb.replace(/\s/g, '');
  if (ca.length >= 5 && cb.length >= 5) {
    const d = distanciaLevenshtein(ca, cb);
    if (d / Math.max(ca.length, cb.length) <= 0.2) return { ok: true, comun: '~similar' };
  }

  return { ok: false, comun: null };
}

/** Distancia de edición (cuántos cambios para pasar de a a b). */
function distanciaLevenshtein(a, b) {
  const m = a.length, n = b.length;
  const dp = Array.from({ length: m + 1 }, (_, i) => {
    const row = new Array(n + 1).fill(0);
    row[0] = i;
    return row;
  });
  for (let j = 0; j <= n; j++) dp[0][j] = j;
  for (let i = 1; i <= m; i++) {
    for (let j = 1; j <= n; j++) {
      dp[i][j] = Math.min(
        dp[i - 1][j] + 1,
        dp[i][j - 1] + 1,
        dp[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1)
      );
    }
  }
  return dp[m][n];
}

/**
 * Valida el pie contra el comprobante. Compara:
 *   - emisor del pie  vs  "De"/"Origen" del comprobante
 *   - receptor del pie vs  "Para"/"Destino" del comprobante
 *
 * Pasa SOLO si TODOS los campos presentes en el comprobante coinciden.
 * Si el comprobante tiene De y Para, ambos deben coincidir (no alcanza con uno).
 * Así se detecta si alguien pone un emisor o receptor falso.
 */
function validarPersonas(pie, lectura) {
  const checks = [];
  if (lectura.de)   checks.push({ campo: 'emisor',   pieVal: pie.emisor,   compVal: lectura.de,   ...nombresCoinciden(pie.emisor, lectura.de) });
  if (lectura.para) checks.push({ campo: 'receptor', pieVal: pie.receptor, compVal: lectura.para, ...nombresCoinciden(pie.receptor, lectura.para) });

  if (!checks.length) return { ok: true, detalle: 'comprobante sin datos para validar' };

  const fallidos = checks.filter(c => !c.ok);
  if (fallidos.length === 0) {
    const det = checks.map(c => `${c.campo} por "${c.comun}"`).join(', ');
    return { ok: true, detalle: `coinciden ${det}` };
  }

  // Al menos un campo NO coincide → la validación falla
  const det = fallidos.map(c => `${c.campo} "${c.pieVal}" ≠ comprobante "${c.compVal}"`).join('; ');
  return { ok: false, detalle: det };
}

// ─── Backend ─────────────────────────────────────────────────────────────────
/**
 * Traduce un error de axios contra el backend a un texto útil. El caso 401 es
 * especial: significa que el backend rechazó la API key del bot — casi siempre
 * porque BOT_API_KEY (bot) y GS_BOT_API_KEY (ms-finanzas) no coinciden, lo que
 * suele pasar cuando se recrea un solo container y quedan desincronizados. Sin
 * este hint el error llega como un opaco "Request failed with status code 401".
 */
function mensajeErrorBackend(e) {
  if (e.response?.status === 401) {
    console.error('[Bot] 401 del backend: la API key del bot fue rechazada. ' +
      'Verificá que GS_BOT_API_KEY sea IGUAL en el bot y en ms-finanzas ' +
      '(recreá ambos juntos: docker compose up -d --force-recreate gs-bot ms-finanzas).');
    return 'el backend rechazó la clave del bot (401). Revisá que GS_BOT_API_KEY coincida en el bot y en ms-finanzas.';
  }
  return e.response?.data?.mensaje || e.message;
}

// Reintenta hasta 3 veces con 3 s de pausa si el backend no responde.
async function registrarPago(datos) {
  const headers = { 'Content-Type': 'application/json' };
  if (BOT_API_KEY) headers['X-Bot-Api-Key'] = BOT_API_KEY;
  const body = {
    receptorNombre:    datos.receptorNombre,
    monto:             datos.monto,
    emisor:            datos.emisor,
    idOperacion:       datos.idOperacion,
    cargadoPorNombre:  datos.cargadoPorNombre,
    cargadoPorTelefono: datos.cargadoPorTelefono,
    grupoOrigen:       datos.grupoOrigen,
    comprobanteBase64: datos.comprobanteBase64,
    comprobanteMime:   datos.comprobanteMime,
    comprobanteNombre: datos.comprobanteNombre,
  };

  const MAX_INTENTOS = 3;
  let lastError;
  for (let intento = 1; intento <= MAX_INTENTOS; intento++) {
    try {
      const res = await axios.post(
        `${BACKEND_URL}/api/finanzas/sueldos/pago-automatico`,
        body,
        { headers, timeout: 15000 },
      );
      return res.data;  // { estado, tipoReceptor, receptorResuelto, mensaje, ... }
    } catch (err) {
      lastError = err;
      if (intento < MAX_INTENTOS) {
        console.warn(`[Bot] Backend no respondió (intento ${intento}/${MAX_INTENTOS}). Reintentando en 3 s…`);
        await new Promise(r => setTimeout(r, 3000));
      }
    }
  }
  throw lastError;
}

// ─── Registro de efectivo (borrador pendiente de confirmación) ───────────────
async function registrarEfectivo(msg, chat, contacto, efectivo, opciones = {}) {
  const { reconciliacion = false } = opciones;
  const prefijoReconciliacion = reconciliacion ? '🔄 _(recuperado tras una desconexión)_\n' : '';
  const responder = (txt) => msg.reply(prefijoReconciliacion + txt);
  const cargadoPorNombre   = contacto.pushname || contacto.name || 'Desconocido';
  const cargadoPorTelefono = contacto.number;
  const montoFmt = efectivo.monto.toLocaleString('es-AR');
  console.log(`\n💵 [${chat.name}] ${cargadoPorNombre} declaró efectivo $${montoFmt} para "${efectivo.receptor}"`);

  if (!BACKEND_ENABLED) {
    await responder(`🧪 *Modo prueba* — Efectivo detectado:\n• Monto: $${montoFmt}\n• Para: ${efectivo.receptor}`);
    return;
  }

  try {
    const headers = { 'Content-Type': 'application/json' };
    if (BOT_API_KEY) headers['X-Bot-Api-Key'] = BOT_API_KEY;
    const res = await axios.post(
      `${BACKEND_URL}/api/finanzas/sueldos/pago-efectivo`,
      { receptorNombre: efectivo.receptor, monto: efectivo.monto, emisor: efectivo.emisor || null, cargadoPorNombre, cargadoPorTelefono, grupoOrigen: chat.name },
      { headers, timeout: 10000 }
    );
    console.log(`[BOT-EFECTIVO] Borrador id=${res.data?.id} creado para "${efectivo.receptor}"${efectivo.emisor ? ` (pagó: ${efectivo.emisor})` : ''}`);
    await responder(
      `💵 Efectivo anotado como *pendiente de confirmación*\n` +
      (efectivo.emisor ? `• Pagó: *${efectivo.emisor}*\n` : '') +
      `• Monto: *$${montoFmt}*\n` +
      `• Para: *${efectivo.receptor}*\n` +
      `_El administrativo lo confirma desde el sistema._`
    );
  } catch (e) {
    console.error('[BOT-EFECTIVO] Error:', e.message);
    await responder(`⚠️ No se pudo registrar el efectivo: ${mensajeErrorBackend(e)}`);
  }
}

// ─── Limpieza periódica de pendientes vencidos ──────────────────────────────
setInterval(() => {
  const ahora = Date.now();
  for (const [k, v] of pendientes) {
    if (ahora - v.ts > TIMEOUT_PENDIENTE) pendientes.delete(k);
  }
  for (const [k, v] of pendientesPie) {
    if (ahora - v.ts > TIMEOUT_PENDIENTE) pendientesPie.delete(k);
  }
}, 60 * 1000);

// ─── API HTTP interna ─────────────────────────────────────────────────────────
// Usada por la UI (estado + QR) y por ms-pedidos (notificaciones proactivas).
// Todos los POST requieren X-Bot-Api-Key coincidente con BOT_API_KEY del .env.
http.createServer((req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, X-Bot-Api-Key');

  // ── GET /api/bot/estado ────────────────────────────────────────────────────
  if (req.method === 'GET' && req.url.startsWith('/api/bot/estado')) {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({
      conectado: estadoBot.conectado,
      qrDataUrl: estadoBot.qrDataUrl,
      motivo: estadoBot.motivo,
      ultimaActualizacion: estadoBot.ultimaActualizacion,
      grupos: GRUPOS,
      backendActivo: BACKEND_ENABLED,
    }));
  }

  // CORS preflight
  if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }

  // Autenticación para todos los POST
  if (req.method === 'POST') {
    if (BOT_API_KEY && req.headers['x-bot-api-key'] !== BOT_API_KEY) {
      res.writeHead(401, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ error: 'API key inválida' }));
    }

    // Leer body JSON
    const chunks = [];
    req.on('data', c => chunks.push(c));
    req.on('end', async () => {
      let body = {};
      try { body = JSON.parse(Buffer.concat(chunks).toString()); } catch (_) { /* sin body */ }

      // ── POST /api/bot/regenerar-qr ────────────────────────────────────────
      if (req.url === '/api/bot/regenerar-qr') {
        try {
          console.log('🔄 Regenerando QR por solicitud de la UI...');
          await client.logout();
          armarWatchdogReady();
          await client.initialize();
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: true, mensaje: 'Cerrando sesión y regenerando QR...' }));
        } catch (e) {
          console.error('Error regenerando QR:', e.message);
          res.writeHead(500, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: e.message }));
        }
        return;
      }

      // ── POST /api/bot/reconciliar ──────────────────────────────────────────
      // Revisa el historial reciente de los grupos por si quedaron comprobantes
      // sin cargar (bot desconectado, reinicio, etc.). Corre en segundo plano —
      // devuelve enseguida y el resultado real queda en los logs del bot y en
      // el historial de "Bot WhatsApp" a medida que va procesando.
      if (req.url === '/api/bot/reconciliar') {
        if (!estadoBot.conectado) {
          res.writeHead(503, { 'Content-Type': 'application/json' });
          return res.end(JSON.stringify({ error: 'Bot no conectado' }));
        }
        console.log('🔄 Reconciliación manual solicitada desde la UI...');
        reconciliarChats(RECONCILIACION_LIMITE_MANUAL).catch(e =>
          console.error('[Reconciliación] Error (manual):', e.message));
        res.writeHead(202, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: true, mensaje: 'Revisión iniciada — puede tardar unos segundos por grupo.' }));
        return;
      }

      // ── POST /api/bot/mensaje ─────────────────────────────────────────────
      // Body: { telefono: string, texto: string }
      // Endpoint genérico para alertas de cualquier microservicio (ej: stock bajo).
      if (req.url === '/api/bot/mensaje') {
        const { telefono, texto } = body;
        if (!telefono || !texto) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          return res.end(JSON.stringify({ error: 'Faltan campos: telefono, texto' }));
        }
        if (!estadoBot.conectado) {
          res.writeHead(503, { 'Content-Type': 'application/json' });
          return res.end(JSON.stringify({ error: 'Bot no conectado' }));
        }
        try {
          const chatId = normalizarTelefono(telefono);
          await client.sendMessage(chatId, texto);
          console.log(`📲 Mensaje genérico enviado a ${chatId}`);
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: true, chatId }));
        } catch (e) {
          console.error('Error enviando mensaje:', e.message);
          res.writeHead(500, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: e.message }));
        }
        return;
      }

      // ── POST /api/bot/notificar ───────────────────────────────────────────
      // Body: { telefono: string, nombre: string, nroPedido: string, trabajo: string }
      if (req.url === '/api/bot/notificar') {
        const { telefono, nombre, nroPedido, trabajo } = body;
        if (!telefono || !nroPedido) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          return res.end(JSON.stringify({ error: 'Faltan campos: telefono, nroPedido' }));
        }
        if (!estadoBot.conectado) {
          res.writeHead(503, { 'Content-Type': 'application/json' });
          return res.end(JSON.stringify({ error: 'Bot no conectado — no se puede enviar mensaje' }));
        }
        try {
          const chatId = normalizarTelefono(telefono);
          const texto =
            `*Laboratorio GS*\n` +
            `Hola ${nombre || 'Dr./Dra.'}, su trabajo *${trabajo || 'trabajo solicitado'}* ` +
            `(pedido *${nroPedido}*) ya está listo para retirar.\n` +
            `_Por favor coordine el retiro con el laboratorio._`;
          await client.sendMessage(chatId, texto);
          console.log(`📲 Notificación WhatsApp enviada a ${chatId} — pedido ${nroPedido}`);
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: true, chatId }));
        } catch (e) {
          console.error('Error enviando notificación WhatsApp:', e.message);
          res.writeHead(500, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: e.message }));
        }
        return;
      }

      res.writeHead(404); res.end();
    });
    return;
  }

  res.writeHead(404); res.end();
}).listen(BOT_HTTP_PORT, () =>
  console.log(`🌐 Bot HTTP: http://localhost:${BOT_HTTP_PORT}/api/bot/estado`));

/** Normaliza el teléfono a formato WhatsApp {countryCode}{number}@c.us.
 *  Soporta formatos argentinos: 011-XXXX-XXXX, +54 9 11 XXXX, 15XXXXXXXX, etc. */
function normalizarTelefono(telefono) {
  const digitos = telefono.replace(/\D/g, '');
  // Si ya tiene código de país Argentina (54) y 12+ dígitos: usar directo
  if (digitos.length >= 12 && digitos.startsWith('54')) return digitos + '@c.us';
  // Si empieza con 0 (formato local): reemplazar 0 inicial por 54
  if (digitos.startsWith('0')) return '54' + digitos.slice(1) + '@c.us';
  // Si empieza con 9 y 11 dígitos (formato sin 0): agregar 54
  return '54' + digitos + '@c.us';
}

// OJO: hubo acá manejadores globales de uncaughtException/unhandledRejection
// que mataban el proceso entero (process.exit) ante CUALQUIER excepción no
// atrapada en cualquier parte del código — no solo en el arranque. Puppeteer
// tira errores internos esporádicos como parte de su funcionamiento normal
// (fuera de nuestras propias promesas, ej. durante el manejo interno de la
// página de WhatsApp Web), y esos handlers terminaban reiniciando el bot en
// bucle apenas llegaba cualquier mensaje — el bot quedaba "Iniciando" para
// siempre y no procesaba nada. Se sacaron. El error real que motivó
// agregarlos (client.initialize() fallando en el arranque) ya está cubierto
// puntualmente más abajo con el .catch() de la propia llamada, que es seguro
// porque solo actúa sobre ESE fallo específico.

console.log('🤖 Iniciando bot de WhatsApp GS...');
armarWatchdogReady();
client.initialize().catch((e) => {
  console.error('❌ No se pudo inicializar el cliente de WhatsApp:', e && e.stack || e);
  process.exit(1);
});

// El scraper de mails (pedidos recibidos por email) corre como servicio Docker
// aparte (gs-mail-scraper, ver Dockerfile.mail-scraper) — así un cuelgue de
// IMAP no puede afectar esta sesión de WhatsApp, y viceversa.
