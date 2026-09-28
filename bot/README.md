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

## 🔁 Mantenerlo andando

- **El celular** con el chip tiene que quedar **prendido y con internet** (en un
  cajón, enchufado). No hace falta tocarlo, pero sí que esté prendido.
- **La sesión se guarda** (`.wwebjs_auth/`): si reiniciás el bot o la compu, se
  reconecta solo sin re-escanear.
- **Si alguna vez pide QR de nuevo** (raro): volvés a escanear desde cualquier
  celular del lab que tenga el número.

---

## ⚙️ Próximas mejoras (cuando funcione la base)
- OCR para leer el monto directo del comprobante (sin escribirlo)
- Subir el comprobante a MinIO y guardar el link
- Recuperación automática de mensajes perdidos al reconectar
- Comando `/atrasados` para consultar pedidos atrasados desde el grupo
- Migrar a `RemoteAuth` (sesión en base de datos) para sobrevivir cambios de PC
