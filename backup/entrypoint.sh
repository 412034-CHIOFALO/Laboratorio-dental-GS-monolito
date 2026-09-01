#!/bin/bash
set -e

# cron NO hereda las variables de entorno del contenedor: cuando el daemon
# dispara un job, le arma un environment mínimo (HOME, PATH, SHELL...) y las
# variables que puso docker-compose (MYSQL_ROOT_PASSWORD, TZ) NO están. Por eso
# el backup de las 3 AM moría en la primera línea (backup.sh tiene `set -u`, así
# que una variable sin definir aborta todo).
#
# Solución: volcamos acá, al arrancar el contenedor, las variables que el job
# necesita a un archivo que la línea de cron hace `source` antes de ejecutar el
# script (ver crontab). Así el job corre con el mismo entorno que el contenedor.
cat > /etc/cron-env <<EOF
export MYSQL_ROOT_PASSWORD='${MYSQL_ROOT_PASSWORD:-}'
export TZ='${TZ:-America/Argentina/Cordoba}'
EOF
chmod 600 /etc/cron-env

echo "[entrypoint] Entorno para cron escrito en /etc/cron-env."

# Aseguramos que el archivo de log exista para poder tail-earlo desde el arranque.
touch /var/log/backup.log

# Servidor de disparo a demanda (el botón de la UI) en segundo plano. El backup
# automático de las 3 AM lo maneja cron; esto agrega el "correr ahora".
echo "[entrypoint] Arrancando servidor de disparo del backup..."
java -cp / TriggerServer &

echo "[entrypoint] Arrancando cron en foreground..."
exec cron -f
