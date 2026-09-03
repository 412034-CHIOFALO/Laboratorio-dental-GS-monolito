# ─── Stage 1: Build Angular ────────────────────────────────
FROM node:20-alpine AS build
WORKDIR /app

# Instalar deps con --legacy-peer-deps (ngx-toastr 19 vs Angular 20)
COPY package.json package-lock.json* ./
RUN npm install --legacy-peer-deps

# Copiar resto del proyecto y compilar producción
COPY . .
RUN npm run build -- --configuration=production

# ─── Stage 2: nginx servidor estático + proxy ─────────────
FROM nginx:1.27-alpine

# Quitar config default
RUN rm /etc/nginx/conf.d/default.conf

# Los 2 templates posibles quedan en un directorio APARTE de
# /etc/nginx/templates/ (el que la imagen oficial escanea con envsubst al
# arrancar) — docker-entrypoint.sh elige cuál de los dos copiar ahí adentro
# en cada arranque, según si ya existe un certificado real para $DOMAIN.
# Sin certificado (dev local, o el primer arranque en prod antes de correr
# init-letsencrypt.sh) siempre gana el plano — mismo comportamiento que antes.
COPY nginx.conf.template       /etc/nginx/templates-src/http.conf.template
COPY nginx.https.conf.template /etc/nginx/templates-src/https.conf.template
RUN mkdir -p /etc/nginx/templates /var/www/certbot

COPY docker-entrypoint.sh /docker-entrypoint-gs.sh
RUN chmod +x /docker-entrypoint-gs.sh

# Copiar el build de Angular
COPY --from=build /app/dist/frontend-app/browser /usr/share/nginx/html

EXPOSE 80 443

# /healthz responde 200 sin redirigir en los 2 templates (a diferencia de
# "/", que en modo HTTPS redirige todo lo demás de :80 a :443).
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost/healthz || exit 1

ENTRYPOINT ["/docker-entrypoint-gs.sh"]
