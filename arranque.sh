#!/usr/bin/env bash
# Pone de pie G&S después de que el servidor se reinició. Lo ejecuta cron con @reboot (ver PRODUCCION.md §12).
#
# Por qué existe: con restart: unless-stopped Docker relanza solo los contenedores al arrancar, pero si el
# plugin de logs 'loki' quedó deshabilitado, app/frontend/backup no pueden crearse (exit 128) y Docker no
# reintenta. Este script habilita el plugin y arranca lo que falló. Si aun así algo no arranca, lo recrea
# sin el envío de logs: el sitio vuelve igual y lo único que se pierde son los logs en Grafana.
#
# Respeta lo apagado a propósito: solo arranca contenedores que fallaron al crearse (State.Error), nunca
# los que alguien paró con 'docker stop' (por ejemplo el bot de WhatsApp).
#
# Uso:  ./arranque.sh            levanta lo que haga falta (escribe en ~/claude/logs/arranque.log)
#       ./arranque.sh --estado   solo informa por pantalla, no toca nada
set -uo pipefail

cd "$(dirname "$(readlink -f "$0")")"
PROYECTO="gs-monolito"
SOLO_ESTADO=0
[ "${1:-}" = "--estado" ] && SOLO_ESTADO=1

if [ "$SOLO_ESTADO" -eq 0 ]; then
    mkdir -p "${HOME}/claude/logs"
    exec >>"${HOME}/claude/logs/arranque.log" 2>&1
fi

exec 9>/tmp/gs-arranque.lock
if ! flock -n 9; then echo "Ya hay otro arranque en curso."; exit 0; fi

ts() { date '+%F %T'; }
leer_env() { grep -E "^$1=" .env 2>/dev/null | head -1 | cut -d= -f2- | tr -d "\"'" || true; }
estado_plugin() { docker plugin ls --format '{{.Name}} {{.Enabled}}' 2>/dev/null | awk '$1 ~ /^loki(:|$)/ {print $2; exit}'; }
fallidos() {
    docker ps -a --filter "label=com.docker.compose.project=${PROYECTO}" --filter "status=exited" --format '{{.Names}}' |
    while read -r c; do
        [ -n "$(docker inspect -f '{{.State.Error}}' "$c" 2>/dev/null)" ] && echo "$c"
    done
}

echo "=== $(ts) $([ "$SOLO_ESTADO" -eq 1 ] && echo 'estado' || echo 'arranque') ==="

# Docker puede tardar en responder justo después de prender el servidor.
for _ in $(seq 1 60); do docker info >/dev/null 2>&1 && break; sleep 3; done
if ! docker info >/dev/null 2>&1; then echo "$(ts) Docker no responde: no se puede hacer nada."; exit 1; fi

if [ "$(leer_env OTEL_ENABLED)" = "true" ]; then
    case "$(estado_plugin)" in
        true) echo "$(ts) Plugin loki habilitado." ;;
        false)
            echo "$(ts) El plugin loki está DESHABILITADO."
            if [ "$SOLO_ESTADO" -eq 0 ]; then
                if docker plugin enable loki >/dev/null 2>&1; then echo "$(ts) Plugin loki habilitado."; else echo "$(ts) No se pudo habilitar el plugin loki."; fi
            fi ;;
        *) echo "$(ts) El plugin loki no está instalado." ;;
    esac
fi

LISTA=$(fallidos)
if [ -z "$LISTA" ]; then
    echo "$(ts) Ningún contenedor falló al crearse: no hay nada que arrancar."
else
    echo "$(ts) Fallaron al arrancar: $(echo "$LISTA" | tr '\n' ' ')"
fi
[ "$SOLO_ESTADO" -eq 1 ] && exit 0

arrancar() { echo "$LISTA" | grep -E "$1" | while read -r c; do docker start "$c" >/dev/null && echo "$(ts) Arrancó $c"; done; }
esperar_sana() {
    for _ in $(seq 1 60); do
        [ "$(docker inspect -f '{{.State.Health.Status}}' "$1" 2>/dev/null)" = "healthy" ] && return 0
        sleep 3
    done
    return 1
}

# La base y el almacenamiento van primero y la app antes que el frontend (nginx resuelve a la app al arrancar).
arrancar '(mysql|minio)'
arrancar '(-app-)'
APP=$(echo "$LISTA" | grep -E '(-app-)' | head -1)
if [ -n "$APP" ]; then
    if esperar_sana "$APP"; then echo "$(ts) $APP sana."; else echo "$(ts) $APP no llegó a estar sana en 3 minutos."; fi
fi
arrancar '(frontend|backup|duckdns|certbot)'
# El bot va al final: espera a que la app esté sana. Solo se toca si FALLÓ al crearse (plugin de logs); un bot
# parado a propósito con 'docker stop' no aparece en $LISTA y se respeta.
arrancar '(gs-bot)'

# Plan B: si algo sigue sin arrancar se recrea sin el overlay de observabilidad (sin plugin de logs).
FALTAN=$(fallidos)
if [ -n "$FALTAN" ]; then
    echo "$(ts) Siguen sin arrancar: $(echo "$FALTAN" | tr '\n' ' ') → plan B: recrearlos sin el envío de logs."
    COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.https.yml)
    [ -n "$(leer_env DUCKDNS_TOKEN)" ] && COMPOSE+=(-f docker-compose.duckdns.yml)
    SERVICIOS_B=(app frontend backup)
    echo "$FALTAN" | grep -q 'gs-bot' && SERVICIOS_B+=(gs-bot)
    if "${COMPOSE[@]}" up -d "${SERVICIOS_B[@]}"; then echo "$(ts) Plan B aplicado."; else echo "$(ts) El plan B también falló: revisar a mano."; fi
fi

echo "$(ts) Estado final:"
docker ps -a --filter "label=com.docker.compose.project=${PROYECTO}" --format '  {{.Names}}  {{.Status}}'
