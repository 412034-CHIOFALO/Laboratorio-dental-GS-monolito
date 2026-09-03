#!/bin/bash
# Bootstrap de HTTPS — se corre UNA SOLA VEZ en el VPS, antes del primer
# "docker compose ... up -d" con docker-compose.https.yml.
#
# Resuelve el huevo-y-la-gallina de Let's Encrypt: para emitir el primer
# certificado hace falta que nginx ya esté arriba respondiendo el challenge
# HTTP-01, pero nginx recién arranca en modo HTTP plano hasta que ese
# certificado exista (ver frontend/docker-entrypoint.sh). Este script:
#   1. Levanta "frontend" (arranca en HTTP plano, sin certificado todavía).
#   2. Le pide el certificado real a Let's Encrypt contra ese nginx.
#   3. Reinicia "frontend" — el entrypoint detecta el certificado nuevo y
#      pasa a servir HTTPS.
#   4. Levanta el resto del stack (incluye "certbot", que queda renovando solo).
#
# Requisitos antes de correr esto:
#   - DNS del dominio (DOMAIN en .env) ya apuntando al IP de este servidor.
#   - .env completo (copiado de .env.example), con DOMAIN y LETSENCRYPT_EMAIL reales.
#
# Uso:
#   ./init-letsencrypt.sh            # certificado real
#   ./init-letsencrypt.sh --staging  # certificado de prueba (Let's Encrypt
#                                     # staging) — para ensayar sin gastar el
#                                     # límite real de 5 certs/semana/dominio.

set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f .env ]; then
    echo "Falta .env (copiá .env.example a .env y completá los valores reales primero)."
    exit 1
fi
set -a
. ./.env
set +a

if [ -z "${DOMAIN:-}" ] || [ "$DOMAIN" = "tu-dominio.com" ]; then
    echo "DOMAIN no está seteado en .env (o sigue con el valor de ejemplo)."
    exit 1
fi
if [ -z "${LETSENCRYPT_EMAIL:-}" ] || [ "$LETSENCRYPT_EMAIL" = "vos@tu-dominio.com" ]; then
    echo "LETSENCRYPT_EMAIL no está seteado en .env (o sigue con el valor de ejemplo)."
    exit 1
fi

STAGING_FLAG=""
if [ "${1:-}" = "--staging" ]; then
    echo "Modo --staging: certificado de PRUEBA (el browser lo va a marcar como no confiable)."
    STAGING_FLAG="--staging"
fi

COMPOSE="docker compose -f docker-compose.yml -f docker-compose.https.yml"

echo "[1/4] Levantando frontend en modo HTTP (bootstrap)..."
$COMPOSE up -d frontend

echo "[2/4] Esperando a que frontend esté sano..."
for i in $(seq 1 20); do
    STATUS=$($COMPOSE ps --format json frontend 2>/dev/null | grep -o '"Health":"[a-z]*"' || true)
    if echo "$STATUS" | grep -q healthy; then break; fi
    sleep 3
done

echo "[3/4] Pidiendo el certificado real a Let's Encrypt para $DOMAIN..."
# --entrypoint solo acepta UN binario (no una línea con espacios) — por eso
# se pisa el entrypoint del servicio (el loop de renovación) de vuelta al
# binario "certbot" solo, y el resto de la línea son args normales de "run".
$COMPOSE run --rm --entrypoint certbot certbot \
  certonly --webroot -w /var/www/certbot \
  -d "$DOMAIN" --email "$LETSENCRYPT_EMAIL" --agree-tos --no-eff-email $STAGING_FLAG

echo "[4/4] Reiniciando frontend (pasa a HTTPS) y levantando el resto del stack..."
$COMPOSE restart frontend
$COMPOSE up -d

echo ""
echo "Listo. https://$DOMAIN debería responder ya (puede tardar unos segundos)."
echo "Verificar con: curl -I https://$DOMAIN/actuator/health"
