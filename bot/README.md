# 🤖 Bot de WhatsApp — Laboratorio GS

Lee los grupos de comprobantes, detecta los pagos triangulados y los registra
en el sistema automáticamente.

## Requisitos
- Node.js LTS instalado (https://nodejs.org)
- El celular con el chip del bot, prendido y con WhatsApp

---

## 🪜 Primera puesta en marcha

### 1. Instalar dependencias
```bash
cd gs-bot-whatsapp
npm install
```
(la primera vez tarda un par de minutos: baja Chromium)

### 2. Configurar
```bash
cp .env.example .env
```
Para la **primera prueba**, dejá `.env` así:
```
BACKEND_ENABLED=false
GRUPOS=
```
Con `BACKEND_ENABLED=false` el bot **solo loguea** lo que detecta, sin tocar el
sistema. Ideal para comprobar que lee bien los grupos.

### 3. Arrancar
```bash
npm start
```
Aparece un **QR en la terminal**.

### 4. Vincular (una sola vez)
En el celular del chip:
**WhatsApp → ⋮ (3 puntos) → Dispositivos vinculados → Vincular un dispositivo → escanear el QR**

Cuando veas `✅ Bot CONECTADO`, ya está. La sesión queda guardada (no volvés a
escanear el QR).

### 5. Crear un grupo de prueba
1. En el celular, creá un grupo de WhatsApp (puede ser con vos mismo + un contacto)
2. El bot ya está dentro (es el número del chip)
3. Mandá una **foto cualquiera** con este texto al pie:
   ```
   50000 Dr. García (Carlos López)
   ```
4. En la terminal del bot vas a ver:
   ```
   📩 ─────────── COMPROBANTE RECIBIDO ───────────
      Grupo:        Prueba
      Cargado por:  Tu Nombre (549351...)
      Monto:        50000
      Emisor:       Dr. García
      Receptor:     Carlos López
   ```

**Si ves eso, el bot funciona.** 🎉

---

## 🔌 Conectar con el sistema (cuando el backend esté levantado)

1. Editá `.env`:
   ```
   BACKEND_ENABLED=true
   BOT_API_KEY=<la misma que GS_BOT_API_KEY del monolito>
   ```
2. Reiniciá el bot (`Ctrl+C` y `npm start`)
3. Mandá un comprobante al grupo → ahora se registra en Finanzas → Sueldos →
   histórico del empleado, con origen **🤖 Bot**

## 🐳 En Docker (producción)

El bot es el servicio `gs-bot` del `docker-compose.yml` de la raíz: arranca
con el resto del stack y toma la config de ahí (no usa este `.env`). La
primera vez, escaneá el QR desde la pantalla **Bot WhatsApp → Estado del bot**
del panel, o desde los logs:
```bash
docker compose logs -f gs-bot
```

---

## 📝 Formato del mensaje que el bot entiende

Mandás la **foto del comprobante** y al pie escribís:
```
EMISOR (RECEPTOR)
```
Ejemplos válidos:
- `Dr. García (Carlos López)`
- `Odontólogo Pérez (Mario Giménez)`
- `Dra. Sánchez (Valentina Torres)`

Donde:
- **EMISOR** = quién pagó (afuera del paréntesis) — normalmente un odontólogo
- **(RECEPTOR)** = quién recibió (adentro del paréntesis) — el integrante del lab

**El monto NO se escribe**: el bot lo lee de la imagen del comprobante con OCR.
El bot identifica **quién cargó** el comprobante por su número de WhatsApp.

> Si el bot no está seguro del monto que leyó, te avisa en el grupo para que
> lo verifiques en el sistema.

---

## 🔍 Cómo lee el monto (y cuándo lo manda a revisión)

- **PDF:** se lee el texto del archivo. Es exacto, no hay OCR.
- **Imagen:** se lee **dos veces** (`ocr.js`): tal cual llegó y una versión preparada (`imagen.js`: orientación
  EXIF, grises, modo oscuro invertido, agrandada si es chica). Después se **vuelve a leer solo la zona del monto**
  con un recorte, restringido a dígitos. Una imagen que no es una imagen (o un sticker) falla antes de llegar a Tesseract.
- **Confianza del monto** (`lecturaMonto.js`): sale "alta" solo si nada se contradice. Baja a "media" (y el pago
  queda *pendiente de revisión*) si las dos lecturas dan montos distintos, si Tesseract duda de los dígitos
  (menos de 70% de seguridad), si el recorte da otro número, o si el monto que escribieron en el pie es distinto
  al del comprobante. La confianza solo baja con estos controles, nunca sube.
- **N° de operación y fecha:** si las dos lecturas dicen cosas distintas no se informan, y el pago pasa a revisión por
  "no se pudo leer".
- **El motivo** llega al panel y a la respuesta del grupo como *"Cómo se leyó el comprobante: …"*.
- **Gemini** (opcional, `GEMINI_ENABLED=true`): solo si las reglas no llegaron a "alta". Lo que propone se da por
  seguro únicamente si el texto leído del comprobante contiene ese mismo monto; si no, queda a revisión.
- **Se reconocen** montos como `$ 85.000`, `$ 85000`, `$85 000`, `85,000`, `85.OOO` (letras por dígitos) y `120.000,00`.
  Un número sin `$` ni separador de miles no se toma por monto (podría ser un CBU).

### Medir con comprobantes reales

Los tests usan imágenes inventadas. Para saber cuánto acierta con las de verdad: juntá comprobantes de las distintas
billeteras en una carpeta **fuera del repositorio** (o en `bot/muestras/`, ignorada por git; tienen datos de terceros),
agregá un `esperados.json` y corré:

```bash
npm run medir -- ../ruta/a/la/carpeta
```
```json
{ "mp-001.jpg": { "monto": 85000, "idOperacion": "98765432101", "fecha": "2026-09-12" },
  "uala-002.png": { "monto": 120000 } }
```
Muestra, por archivo, lo esperado contra lo leído y la confianza; y un resumen. El número que importa es
**"errores con confianza alta": tiene que ser 0** (un monto mal leído que el bot registraría solo).

---

## 🔁 Mantenerlo andando

- **El celular** con el chip tiene que quedar **prendido y con internet** (en un
  cajón, enchufado). No hace falta tocarlo, pero sí que esté prendido.
- **La sesión se guarda** (`.wwebjs_auth/`): si reiniciás el bot o la compu, se
  reconecta solo sin re-escanear.
- **Si alguna vez pide QR de nuevo** (raro): volvés a escanear desde cualquier
  celular del lab que tenga el número.

---

## ⚙️ Próximas mejoras (cuando funcione la base)
- Subir el comprobante a MinIO y guardar el link
- Recuperación automática de mensajes perdidos al reconectar
- Comando `/atrasados` para consultar pedidos atrasados desde el grupo
- Migrar a `RemoteAuth` (sesión en base de datos) para sobrevivir cambios de PC

## whatsapp-web.js: por qué se instala desde un commit

`package.json` apunta a un commit de la rama principal de `wwebjs/whatsapp-web.js` (`064a3d5a`) en vez de la
versión publicada. La última publicada (1.34.7, abril de 2026) **no funciona con el WhatsApp Web actual**:
WhatsApp renombró por dentro `id._serialized` a `id.$1` y `getChats()` / `getChatById()` fallan con
`r: r`, así que el bot recibe los mensajes pero no puede saber de qué grupo son y los ignora. El arreglo
(#201832) está en la rama principal desde el 26/09/2026 pero no se publicó.

**Volver a la versión publicada** apenas salga una posterior a 1.34.7 que lo incluya (`npm view whatsapp-web.js
versions`): cambiar la dependencia a `^1.35.0` (o la que corresponda), `npm install`, `npm test` y probar con
un mensaje real en el grupo. Si WhatsApp vuelve a cambiar algo y el bot deja de recibir, ver `npm view`, los
issues de `wwebjs/whatsapp-web.js` y probar con la rama principal; el bot tiene `GRUPOS_JIDS` como salida
de emergencia para no depender de la resolución en vivo del nombre del grupo.
