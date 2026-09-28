# Bot de WhatsApp — Node + Chromium (whatsapp-web.js corre sobre puppeteer)
FROM node:20-bookworm-slim

# Chromium del sistema + las librerías que puppeteer necesita para correr headless
RUN apt-get update && apt-get install -y --no-install-recommends \
      chromium \
      ca-certificates fonts-liberation \
      libnss3 libatk-bridge2.0-0 libatk1.0-0 libcups2 libdrm2 libgbm1 \
      libgtk-3-0 libasound2 libxshmfence1 libxcomposite1 libxdamage1 libxrandr2 \
    && rm -rf /var/lib/apt/lists/*

# Puppeteer usa el Chromium del sistema (no descarga el suyo)
ENV PUPPETEER_SKIP_DOWNLOAD=true \
    PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium \
    NODE_ENV=production

WORKDIR /app
COPY package*.json ./
RUN npm install --omit=dev
COPY . .

# La sesión de WhatsApp (LocalAuth) se persiste en este path → volumen en compose.
# Así el bot se reconecta solo y NO vuelve a pedir el QR en cada reinicio.
VOLUME ["/app/.wwebjs_auth"]

CMD ["node", "index.js"]
