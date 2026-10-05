#!/bin/bash
# Carga datos de muestra en la instalación del servidor, por la API real.
# Qué carga y cómo: ver el encabezado de cargar_datos_demo.py.
#
#   cd /opt/gs-monolito && ./demo/cargar-demo.sh
#
# Los secretos se leen del .env y viajan solo por el entorno del proceso Python
# (no se imprimen ni quedan en el historial de la shell).
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] || { echo "Falta .env en $(pwd)"; exit 1; }

leer_env() {
    local v
    v=$(grep -E "^$1=" .env | tail -1 | cut -d= -f2- | tr -d '\r') || true
    v="${v%\"}"; v="${v#\"}"; v="${v%\'}"; v="${v#\'}"
    printf '%s' "$v"
}

export GS_ADMIN_PASSWORD GS_BOT_API_KEY DB_ROOT_PASSWORD
GS_ADMIN_PASSWORD=$(leer_env GS_ADMIN_PASSWORD)
GS_BOT_API_KEY=$(leer_env GS_BOT_API_KEY)
DB_ROOT_PASSWORD=$(leer_env DB_ROOT_PASSWORD)
DOMINIO=$(leer_env DOMAIN)

# Por defecto le pega al nginx del propio servidor (loopback + Host del dominio).
# Los argumentos extra pisan estos (p. ej. --base http://IP:8080 para un ensayo).
exec python3 demo/cargar_datos_demo.py --base https://127.0.0.1 --host "$DOMINIO" \
     --mysql-container gs-monolito-mysql-1 "$@"
