# Ir a producción — checklist

Todo el código/infra ya está listo. Esto es lo que queda, en orden, y es todo
pasos externos al repo (cuentas, DNS, correr un par de comandos una sola vez
en el VPS).

## 1. DNS

Apuntar el dominio real (A record) al IP del VPS. Sin esto, Let's Encrypt no
puede validar el dominio en el paso 3.

## 2. Completar `.env`

En el VPS, copiar `.env.example` a `.env` y completar los valores reales —
en particular `DOMAIN` y `LETSENCRYPT_EMAIL` (nuevos), que tienen que
coincidir con el dominio del paso 1 y con `AUTH_ISSUER`.

Dos cosas a chequear antes de seguir:
- Que no haya quedado ningún `cambiar-esto` / `cambiar-en-produccion` sin
  reemplazar: `grep cambiar .env` no debería devolver nada.
- Permisos del archivo, para que solo el usuario que despliega pueda leerlo:
  `chmod 600 .env`.

**Desde la versión del 28/09/2026 el backend no arranca** si algún secreto del
`.env` está vacío, tiene menos de 8 caracteres, es su valor por defecto o
contiene "cambiar" (lo valida `SecretosProduccionValidator` y el error de
`docker logs <proyecto>-app-1` dice exactamente cuáles faltan). Lo que cambió
respecto de la primera instalación:
- `GS_KEYSTORE_PASSWORD` **ya no puede quedar comentada**: poné una propia (8+
  caracteres). Si el keystore del volumen se creó con la contraseña vieja por
  defecto, no se va a poder abrir: bajá los contenedores y borrá ese volumen
  (se regenera solo; lo único que pasa es que todos tienen que volver a loguearse):
  ```bash
  docker compose -f docker-compose.yml -f docker-compose.https.yml down
  docker volume rm gs-monolito_app_keys
  ```
- `GS_TOKEN_TTL_HOURS` pasó a `GS_TOKEN_TTL_MINUTES` (30 por defecto).
- `BOT_URI` ya no va en el `.env` (el compose lo fija solo).

No hace falta ningún gestor de secrets (Vault y similares) para este tamaño
de despliegue — un `.env` en el VPS (gitignoreado, con permisos acotados) es
el patrón estándar para un solo servidor. Un gestor dedicado empieza a valer
la pena recién con varios entornos/servidores o gente con acceso que
necesita permisos separados por secreto — no es este caso.

Nota sobre `.github/workflows/cd.yml`: corre tests y sube las imágenes a
ghcr.io en cada push a `master`, pero **no** hace deploy automático al VPS —
el servidor no está prendido 24/7, así que el paso de subir/levantar los
contenedores queda manual (ver paso 4). Los secrets `VPS_HOST`/`VPS_USER`/
`VPS_SSH_KEY` quedaron guardados en el repo sin uso; no hace falta borrarlos.

## 3. Emitir el certificado (una sola vez)

```bash
./init-letsencrypt.sh
```

Si querés ensayar antes sin gastar el límite real de Let's Encrypt (5
certs/semana por dominio), agregá `--staging` — el certificado que da no es
válido (el browser lo marca como no confiable) pero sirve para confirmar que
todo el circuito (DNS, challenge, nginx) funciona antes de pedir el real.

## 4. Deploy normal (día a día, desde acá en adelante)

Cada push a `master` deja una imagen nueva lista en ghcr.io (ver
`.github/workflows/cd.yml`) — el deploy es manual a propósito (el servidor no
está prendido 24/7). Cuando lo prendas y quieras actualizar, en el servidor:

```bash
cd /opt/gs-monolito
./deploy.sh
```

La carpeta real es `/opt/gs-monolito` (no aparece con `ls` desde tu carpeta personal
porque `/opt` cuelga de la raíz del sistema). Para encontrarla fácil existe el acceso
directo `~/gs-monolito`; los scripts resuelven la ruta real, así que andan igual por
cualquiera de las dos. Todo lo que arma Claude por SSH (logs de deploy, dumps previos,
datos y contraseñas de muestra) va a `~/claude/{logs,backups,demo}`, no suelto en `~`.

Hace, en orden: `git pull` (los `docker-compose*.yml` viven en el repo del
servidor: sin traerlos, un servicio nuevo como `gs-bot` no existe para el
compose viejo), **chequea el `.env` antes de tocar nada** (mismo criterio que
el backend: sin secretos vacíos, cortos, por defecto ni con "cambiar"), baja
las imágenes nuevas, levanta, espera a que `app` quede sana y, si no queda,
muestra su log y qué mirar. Suma solo los overlays opcionales según el `.env`
(DuckDNS si hay `DUCKDNS_TOKEN`; logs a Loki si `OTEL_ENABLED=true` y el plugin
está instalado), así no hay que acordarse de ningún `-f`.

- `./deploy.sh --chequeo` solo valida el `.env`, sin tocar nada. Conviene
  correrlo antes de bajar una versión que cambia variables.
- `./deploy.sh --sin-bot` no toca `gs-bot` (corre Chromium: si el servidor anda
  justo de RAM, dejarlo para después).
- **Nunca `docker compose down -v`**: la `-v` borra los volúmenes (base de
  datos, archivos, certificados, sesión de WhatsApp). Sin `-v` es seguro.
- `backup/` se arma en el propio servidor (no viene de ghcr.io): si cambió algo
  ahí, `docker compose build backup && docker compose up -d backup`.

### Volver atrás

Cada imagen queda también con el tag del commit (`:<sha>`, visible en la sección
Packages del repo en GitHub). Para volver a una versión anterior, en el `.env`:

```
GS_IMAGE=ghcr.io/412034-chiofalo/laboratorio-dental-gs-monolito:<sha>
GS_FRONTEND_IMAGE=ghcr.io/412034-chiofalo/laboratorio-dental-gs-monolito-frontend:<sha>
```

y `docker compose -f docker-compose.yml -f docker-compose.https.yml up -d app frontend`.
Las migraciones de la base no se deshacen: la versión vieja convive con las
columnas nuevas (todas con valor por defecto), pero no sirve volver a una
anterior a la V1.

La renovación del certificado es automática (el servicio `certbot` reintenta
cada 12h, nginx recarga solo cada 6h) — no hace falta tocar nada más.

## 5. Observabilidad — Grafana Cloud (esto es 100% tuyo, no puedo crear la cuenta por vos)

El código ya manda logs/métricas/trazas, solo falta activarlo:

1. Crear cuenta free en [grafana.com](https://grafana.com/auth/sign-up/create-user).
2. En tu stack de Grafana Cloud → **Connections → OpenTelemetry**: copiar el
   endpoint OTLP y el header de autorización (`Authorization: Basic ...`).
3. En **Connections → Loki**: copiar la URL con usuario:token embebido.
4. Pegar los 3 valores en `.env`: `OTEL_EXPORTER_OTLP_ENDPOINT`,
   `GRAFANA_OTLP_AUTH_HEADER`, `LOKI_URL`. Poner `OTEL_ENABLED=true`.
5. En el VPS, instalar el plugin de logging de Docker (una sola vez):
   ```bash
   docker plugin install grafana/loki-docker-driver:latest --alias loki --grant-all-permissions
   ```
6. A partir de acá, sumar el overlay de observabilidad al deploy:
   ```bash
   docker compose -f docker-compose.yml -f docker-compose.https.yml -f docker-compose.observability.yml up -d
   ```
   El deploy es manual (ver nota del paso 2), así que alcanza con acordarte de
   sumar `-f docker-compose.observability.yml` vos mismo a partir de ahora
   cada vez que actualices — no hay ningún pipeline automático que lo tenga
   que saber.

### Verificar que llegó

- `curl -I https://tu-dominio.com/actuator/health` → tiene que dar `200`.
- En Grafana Cloud → **Explore**: elegir el datasource de Loki, buscar
  `{app="gs-monolito"}` y confirmar que aparecen logs recientes. Elegir el
  datasource de Tempo y confirmar que aparecen trazas — un log y una traza
  del mismo request comparten `traceId` (correlación automática, ya
  configurada en `logback-spring.xml`).

### 2 alertas recomendadas (Grafana Cloud → Alerting → New alert rule)

**Backup fallido** (LogQL, datasource Loki):
```
count_over_time({app="gs-monolito"} |= "Backup fallido" [1h]) > 0
```

**Tasa alta de errores 5xx** (PromQL, datasource del OTLP de métricas):
```
sum(rate(http_server_requests_seconds_count{outcome="SERVER_ERROR"}[5m])) > 0.1
```

## 6. Probar un restore de backup real (antes de cargar datos reales del laboratorio)

El backup automático corre solo desde el día 0 (ver `backup/README.md`), pero
nunca se ejecutó una restauración de punta a punta. Antes de considerar esto
listo para datos reales, conviene bajar un dump real y restaurarlo (contra
una base de prueba, no la real) siguiendo el comando ya documentado en
`backup/README.md` → "Restaurar un backup".

## 7. El sitio no responde desde afuera (servidor en una casa)

Con el servidor en una conexión hogareña hay 4 eslabones entre el visitante y
nginx, y cualquiera puede romperse sin que el servidor tenga nada raro. Se
revisan en este orden:

1. **¿La IP del dominio sigue siendo la tuya?** La IP pública de una casa
   cambia (reinicio del módem, el proveedor, cambio de router). Comparar:
   ```bash
   curl -s https://api.ipify.org                                   # la IP pública de tu casa hoy
   curl -s "https://dns.google/resolve?name=TU-SUBDOMINIO.duckdns.org&type=A"   # la que tiene DuckDNS (campo "data")
   ```
   Si son distintas, el dominio apunta a otra casa: entrá a duckdns.org y apretá
   "update ip", o dejá el actualizador automático (`docker-compose.duckdns.yml`,
   ver el encabezado del archivo para las 2 variables del `.env`) para que no
   vuelva a pasar.
2. **¿El router reenvía los puertos 80 y 443 al servidor?** Si cambiás de
   router (o el router se resetea) las reglas se pierden. Hay que crearlas de
   nuevo (TCP 80 y 443 → IP LAN del servidor) y, para que no se rompan solas,
   **reservar esa IP para el servidor en el DHCP del router** (por su MAC): si
   la IP LAN cambia, el reenvío apunta a un equipo que ya no existe.
3. **¿El servidor está prendido y con qué IP?** Desde su terminal (o la web de
   Server Pilot): `hostname -I` y `docker ps` (los 5-6 contenedores arriba).
4. **¿Es un problema solo de tu compu?** Desde la misma red el router no suele
   dejar entrar por su propia IP pública ("NAT loopback"), así que **probar
   siempre desde afuera**: el celular con datos móviles (WiFi apagado) o
   canyouseeme.org en los puertos 80 y 443. Si en esa compu agregaste una línea
   `IP-LAN  tu-dominio` al archivo `hosts` de Windows (para probar sin salir a
   internet), **actualizala o borrala cuando cambie la IP LAN del servidor**:
   si no, el dominio te resuelve a una dirección muerta solo a vos.

Si el sitio abre pero **guardar algo o cerrar sesión da 403**, es la protección
CSRF: el navegador no está mandando el header `X-XSRF-TOKEN` porque no tiene la
cookie `XSRF-TOKEN`. Chequeo (DevTools → Application → Cookies): tiene que existir
`XSRF-TOKEN` y seguir ahí después de navegar por el dashboard. Si aparece y
desaparece, mirá en Network los GET `/api/...`: un `set-cookie: XSRF-TOKEN=;
Max-Age=0` es el backend borrándola.

Esto pasó de verdad (versión anterior al commit `079e0be`): el filtro de la
cookie JWT deja la autenticación sin pasar por un repositorio de sesión, y Spring
rotaba —borraba— la cookie XSRF en cada GET que ya la traía. Está corregido y hay
dos pruebas: `CsrfCookieWebTest` (el backend entrega la cookie) y
`CsrfCookieNoSeBorraWebTest` (no la borra en los GET siguientes). Si vuelve a
pasar con esa versión o una posterior, la causa está entre el backend y el
navegador (un proxy o extensión que filtre `Set-Cookie`).

## 8. Firewall del VPS (recomendado, fuera del repo)

Con `docker-compose.https.yml` los únicos puertos que necesitan estar abiertos
al mundo son 80, 443 y el de SSH:

```bash
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw enable
```

MySQL, MinIO y el backend (8080) ya no publican puertos al host (ver
`docker-compose.yml`) — solo son alcanzables entre contenedores. Si alguna vez
hace falta entrar a la consola de administración de MinIO (puerto 9001) desde
tu compu, un túnel SSH sin abrir nada al público:

```bash
ssh -L 9001:localhost:9001 usuario@tu-vps
# y después abrís http://localhost:9001 en tu compu
```

## 9. Datos de muestra para probar el flujo de punta a punta

Con la base vacía no hay nada que probar. `demo/cargar_datos_demo.py` carga datos
ficticios **por la API real** (así corren las reglas de negocio: stock por receta,
deuda al entregar, caja con cada cobro): 10 odontólogos, 34 pedidos en todos los
estados con documentos y escaneos 3D, comprobantes con distinta mora, caja,
proveedores y deudas, sueldos, pagos "del bot" y reportes mensuales. Las fechas
se reparten en los últimos ~45 días.

```bash
cd /opt/gs-monolito
./demo/cargar-demo.sh        # después de hacer el dump de abajo
```

- **Antes de cargar**, un dump para poder volver al estado limpio:
  ```bash
  docker exec -e MYSQL_PWD="$(grep ^DB_ROOT_PASSWORD= .env | cut -d= -f2-)" gs-monolito-mysql-1 \
    mysqldump -uroot --single-transaction --routines gs_auth | gzip > ~/claude/backups/gs-pre-demo-$(date +%F).sql.gz
  ```
- Corre **una sola vez** (si ya hay odontólogos se frena; `--forzar` lo saltea).
- Crea 3 usuarios de muestra (`tecnico2`, `tecnico3`, `administrativa`) con contraseñas
  al azar en `~/claude/demo/credenciales.txt` (chmod 600). `admin` y `tecnico1` siguen con las del `.env`.
- Los odontólogos **no tienen teléfono** a propósito: al pasar un pedido a LISTO el sistema
  avisa por WhatsApp si hay teléfono, y no queremos escribirle a un número real. Para probar
  esa notificación, ponele tu propio número a uno.
- Los pagos del bot se simulan llamando al mismo endpoint que usa el bot (no hace falta WhatsApp).
- **Antes de usar el sistema de verdad** hay que sacar estos datos. Mientras la base no tenga
  datos reales, lo más limpio es restaurar el dump de arriba (con el sistema parado:
  `docker compose ... stop app`, `gunzip -c dump | docker exec -i ... mysql -uroot gs_auth`,
  `... start app`). Y borrar o cambiar la contraseña de los 3 usuarios de muestra.
