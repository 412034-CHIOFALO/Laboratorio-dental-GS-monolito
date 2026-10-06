#!/bin/bash
# Deploy manual de gs-monolito, en el servidor. Hace, en orden:
#   1. trae el código (los docker-compose*.yml viven en el repo del servidor:
#      sin este paso un servicio nuevo, como gs-bot, no existe para el compose viejo)
#   2. chequea el .env ANTES de tocar nada
#   3. baja las imágenes que armó el CD en ghcr.io
#   4. levanta, espera a que "app" quede sana y, si no, muestra por qué
#
# Uso:
#   ./deploy.sh              actualiza app, frontend y gs-bot
#   ./deploy.sh --sin-bot    no toca gs-bot (el bot corre Chromium: si el servidor
#                            anda justo de RAM, conviene dejarlo para después)
#   ./deploy.sh --chequeo    solo valida el .env y sale, sin tocar nada
#
# NUNCA "docker compose down -v": la -v borra los volúmenes (base de datos,
# archivos, certificados, sesión de WhatsApp). Sin -v, "down" es seguro.
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"   # ruta real aunque se entre por un acceso directo

MODO="deploy"; CON_BOT=1
for a in "$@"; do
    case "$a" in
        --sin-bot) CON_BOT=0 ;;
        --chequeo) MODO="chequeo" ;;
        *) echo "Opción desconocida: $a (ver el encabezado de este archivo)"; exit 1 ;;
    esac
done

[ -f .env ] || { echo "Falta .env: copiá .env.example y completalo (ver PRODUCCION.md)."; exit 1; }

# Lee una variable del .env SIN ejecutarlo como shell: hay valores con espacios
# (el header de Grafana es "Basic abc...") que romperían un "source .env".
leer_env() {
    local v
    v=$(grep -E "^$1=" .env | tail -1 | cut -d= -f2- | tr -d '\r') || true
    v="${v%\"}"; v="${v#\"}"; v="${v%\'}"; v="${v#\'}"
    printf '%s' "$v"
}

# ── Chequeo del .env: el mismo criterio que SecretosProduccionValidator del
# backend (vacío, menos de 8 caracteres, valor por defecto o con "cambiar").
# Mejor enterarse acá que con "app" caída y la versión anterior ya reemplazada.
fallos=()
revisar() {   # revisar VARIABLE [valor-por-defecto-conocido ...]
    local nombre="$1"; shift
    local v; v=$(leer_env "$nombre")
    if [ -z "$v" ] || [ "${#v}" -lt 8 ] || [[ "${v,,}" == *cambiar* ]]; then
        fallos+=("$nombre"); return
    fi
    local d
    for d in "$@"; do
        if [ "$v" = "$d" ]; then fallos+=("$nombre"); return; fi
    done
}
revisar GS_ADMIN_PASSWORD       admin123
revisar GS_TECNICO_PASSWORD     tecnico123
revisar GS_BOT_PEDIDOS_PASSWORD cambiar-en-produccion
revisar GS_INTERNAL_API_KEY     gs-internal-key-cambiar-en-prod
revisar GS_BOT_API_KEY          gs-bot-dev-key-cambiar-en-prod
revisar GS_KEYSTORE_PASSWORD    gs_keystore_2025
revisar DB_PASSWORD             gs_app
revisar DB_ROOT_PASSWORD
revisar MINIO_ROOT_PASSWORD     minioadmin

DOMINIO=$(leer_env DOMAIN)
if [ -z "$DOMINIO" ] || [ "$DOMINIO" = "tu-dominio.com" ]; then fallos+=("DOMAIN"); fi

if [ "${#fallos[@]}" -gt 0 ]; then
    echo "El .env tiene valores que la app (o el HTTPS) va a rechazar:"
    printf '  - %s\n' "${fallos[@]}"
    echo "Corregilos y volvé a correr ./deploy.sh — no se tocó nada del servidor."
    exit 1
fi
if [ "$(leer_env SPRING_PROFILES_ACTIVE)" != "prod" ]; then
    echo "AVISO: SPRING_PROFILES_ACTIVE no es 'prod' en el .env."
fi
echo ".env OK."
[ "$MODO" = "chequeo" ] && exit 0

# ── Qué archivos de compose usar. Los overlays opcionales se suman solos según
# lo que haya en el .env, así no hay que acordarse de ningún "-f".
COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.https.yml)
SERVICIOS=(app frontend)
[ "$CON_BOT" -eq 1 ] && SERVICIOS+=(gs-bot)

if [ -n "$(leer_env DUCKDNS_TOKEN)" ]; then
    COMPOSE+=(-f docker-compose.duckdns.yml)
    SERVICIOS+=(duckdns)
fi
if [ "$(leer_env OTEL_ENABLED)" = "true" ]; then
    if docker plugin ls 2>/dev/null | grep -q loki; then
        COMPOSE+=(-f docker-compose.observability.yml)
    else
        echo "AVISO: OTEL_ENABLED=true pero falta el plugin de Docker 'loki' (ver PRODUCCION.md, paso 5):"
        echo "       se sigue sin el envío de logs (métricas y trazas no dependen del plugin)."
    fi
fi

echo "[1/4] Trayendo el código..."
git pull --ff-only

echo "[2/4] Imágenes que estaban corriendo (por si hay que volver atrás):"
"${COMPOSE[@]}" images "${SERVICIOS[@]}" 2>/dev/null || true

echo "[3/4] Bajando las imágenes nuevas..."
"${COMPOSE[@]}" pull "${SERVICIOS[@]}"

echo "[4/4] Levantando..."
# backup se arma en el propio servidor (no viene de ghcr.io): sin esto un cambio en backup/ no se aplicaría.
"${COMPOSE[@]}" build backup
"${COMPOSE[@]}" up -d "${SERVICIOS[@]}" backup

echo "Esperando a que app quede sana (con migraciones nuevas puede tardar un par de minutos)..."
cid=$("${COMPOSE[@]}" ps -q app)
[ -n "$cid" ] || { echo "El contenedor app no se creó. Ver: ${COMPOSE[*]} ps"; exit 1; }
estado="?"
for _ in $(seq 1 60); do
    estado=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}sin-healthcheck{{end}}' "$cid" 2>/dev/null || echo "?")
    case "$estado" in healthy|unhealthy) break ;; esac
    sleep 5
done

if [ "$estado" != "healthy" ]; then
    echo
    echo "app NO quedó sana (estado: $estado). Últimas líneas de su log:"
    docker logs --tail 40 "$cid" 2>&1 | cut -c1-300
    echo
    echo "Si el error nombra una variable del .env o el keystore, ver PRODUCCION.md (sección 2)."
    echo "Para volver a la versión anterior: PRODUCCION.md → 'Volver atrás'."
    exit 1
fi

docker image prune -f > /dev/null || true
"${COMPOSE[@]}" ps
echo
echo "Listo. Probalo desde afuera de tu red: https://$DOMINIO"
