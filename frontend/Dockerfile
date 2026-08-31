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

# Copiar el template (la imagen oficial corre envsubst sobre /etc/nginx/templates/*.template
# al arrancar y escribe el resultado en /etc/nginx/conf.d/ — así se inyecta GS_BOT_API_KEY
# sin hardcodearlo en el repo).
COPY nginx.conf.template /etc/nginx/templates/default.conf.template

# Copiar el build de Angular
COPY --from=build /app/dist/frontend-app/browser /usr/share/nginx/html

# nginx ya expone 80 por default, pero lo declaramos por claridad
EXPOSE 80

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
