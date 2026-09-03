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

## 3. Emitir el certificado (una sola vez)

```bash
./init-letsencrypt.sh
```

Si querés ensayar antes sin gastar el límite real de Let's Encrypt (5
certs/semana por dominio), agregá `--staging` — el certificado que da no es
válido (el browser lo marca como no confiable) pero sirve para confirmar que
todo el circuito (DNS, challenge, nginx) funciona antes de pedir el real.

## 4. Deploy normal (día a día, desde acá en adelante)

```bash
docker compose -f docker-compose.yml -f docker-compose.https.yml up -d
```

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

## 7. Firewall del VPS (recomendado, fuera del repo)

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
