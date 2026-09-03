#!/bin/sh
# Elige qué config de nginx usar ANTES de dejar que arranque el entrypoint
# real de la imagen oficial (que es el que corre envsubst sobre
# /etc/nginx/templates/*.template y escribe el resultado en
# /etc/nginx/conf.d/). Ver docker-compose.https.yml / init-letsencrypt.sh
# para cómo se llega a tener DOMAIN + el certificado.
set -eu

CERT_PATH="/etc/letsencrypt/live/${DOMAIN:-}/fullchain.pem"

if [ -n "${DOMAIN:-}" ] && [ -f "$CERT_PATH" ]; then
    echo "[entrypoint] Certificado encontrado para $DOMAIN — sirviendo HTTPS."
    cp /etc/nginx/templates-src/https.conf.template /etc/nginx/templates/default.conf.template
else
    echo "[entrypoint] Sin DOMAIN o sin certificado todavía — sirviendo HTTP plano."
    cp /etc/nginx/templates-src/http.conf.template /etc/nginx/templates/default.conf.template
fi

# Recarga periódica: certbot renueva pisando el archivo del certificado en el
# volumen compartido, pero nginx no relee un cert nuevo hasta un reload. Sin
# esto, una renovación automática quedaría sin efecto hasta el próximo
# redeploy manual. Un reload de nginx no corta conexiones en curso.
(
    while true; do
        sleep 6h
        nginx -s reload 2>/dev/null || true
    done
) &

exec /docker-entrypoint.sh nginx -g "daemon off;"
