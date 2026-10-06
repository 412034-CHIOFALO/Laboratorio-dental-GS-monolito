#!/bin/bash
# Prueba de restauración: levanta un MySQL DESCARTABLE (sin tocar el de verdad), le restaura un dump y
# muestra qué tiene. Un backup que nunca se restauró no está probado.
#
#   ./backup/probar-restauracion.sh                    usa el backup local más nuevo
#   ./backup/probar-restauracion.sh ruta/al/gs_auth.sql.gz
#
# Corre en el servidor, en la carpeta del proyecto. No toca los contenedores ni los volúmenes reales.
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")/.."

DUMP="${1:-}"
if [ -z "$DUMP" ]; then
  ULTIMO=$(ls -1dt backups/*/ 2>/dev/null | head -1 || true)
  [ -n "$ULTIMO" ] || { echo "No hay backups locales en ./backups. Corré antes: docker compose exec backup /backup.sh"; exit 1; }
  DUMP="${ULTIMO}gs_auth.sql.gz"
fi
[ -f "$DUMP" ] || { echo "No existe $DUMP"; exit 1; }

NOMBRE="gs-restore-prueba-$$"
CLAVE="prueba-$(date +%s)"
MYSQL_IMG=$(grep -E '^\s+image: mysql:' docker-compose.yml | head -1 | awk '{print $2}')
MYSQL_IMG=${MYSQL_IMG:-mysql:8.0}
trap 'docker rm -f "$NOMBRE" >/dev/null 2>&1 || true' EXIT

echo "Dump: $DUMP ($(du -h "$DUMP" | cut -f1)) — imagen: $MYSQL_IMG"
gzip -t "$DUMP" && echo "✓ el archivo descomprime bien"
zcat "$DUMP" | tail -n 3 | grep -q "Dump completed" && echo "✓ el dump está completo" || { echo "✗ el dump está CORTADO"; exit 1; }

docker run -d --name "$NOMBRE" -e MYSQL_ROOT_PASSWORD="$CLAVE" -e MYSQL_DATABASE=gs_auth "$MYSQL_IMG" >/dev/null
# OJO: "mysqladmin ping" da OK aun con acceso denegado, y la imagen de MySQL levanta primero un servidor
# temporal sin clave para inicializarse. Se espera a poder AUTENTICARSE y ejecutar una consulta.
echo -n "Esperando al MySQL de prueba"
LISTO=0
for i in $(seq 1 90); do
  if docker exec -e MYSQL_PWD="$CLAVE" "$NOMBRE" mysql -uroot -N -e 'select 1' gs_auth >/dev/null 2>&1; then LISTO=1; break; fi
  echo -n "."; sleep 2
done
echo
[ "$LISTO" = 1 ] || { echo "✗ el MySQL de prueba no arrancó a tiempo"; exit 1; }

zcat "$DUMP" | docker exec -i -e MYSQL_PWD="$CLAVE" "$NOMBRE" mysql -uroot gs_auth
echo "✓ restaurado sin errores"

q() { docker exec -e MYSQL_PWD="$CLAVE" "$NOMBRE" mysql -uroot -N gs_auth -e "$1" 2>/dev/null; }
TABLAS=$(q "select count(*) from information_schema.tables where table_schema='gs_auth' and table_type='BASE TABLE'")
echo "Tablas restauradas: $TABLAS"
printf '%-26s %s\n' "tabla" "filas"
for t in usuarios odontologos pedidos comprobantes pagos_cuenta_corriente caja_movimientos materiales movimientos_stock proveedores registros_pago_bot flyway_schema_history; do
  printf '%-26s %s\n' "$t" "$(q "select count(*) from $t" || echo '(no existe)')"
done
[ "${TABLAS:-0}" -ge 20 ] || { echo "✗ faltan tablas (se esperaban 20 o más)"; exit 1; }
echo "✓ Restauración probada: el backup sirve."
