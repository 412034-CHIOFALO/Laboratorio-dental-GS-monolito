#!/bin/bash
set -uo pipefail

# Defensa por si se corre fuera de cron: si MYSQL_ROOT_PASSWORD no está en el
# entorno pero sí en el archivo que dejó el entrypoint, lo cargamos. (Desde cron
# ya lo hace la propia línea del crontab; esto cubre el caso de correrlo a mano.)
if [ -z "${MYSQL_ROOT_PASSWORD:-}" ] && [ -f /etc/cron-env ]; then
  . /etc/cron-env
fi

FECHA=$(date +%Y-%m-%d_%H-%M)
TMP_DIR="/backups/tmp/$FECHA"
BASES="gs_auth gs_catalogo gs_pedidos gs_finanzas gs_stock"
REMOTE="gdrive:gs-backups/$FECHA"

# Deja un registro en la tabla de auditoria (la misma que usa ms-auth) para que
# el backup aparezca y se pueda filtrar/buscar en la pantalla de Auditoria del
# dashboard. Se hace por SQL directo (no HTTP) para no depender de que el
# gateway/ms-auth esten arriba a las 3 AM.
registrar_auditoria() {
  local accion="$1" detalle="$2"
  local ahora
  ahora=$(date '+%Y-%m-%d %H:%M:%S')
  local detalle_escapado
  detalle_escapado=$(printf '%s' "$detalle" | sed "s/'/''/g")
  mysql -h mysql -uroot -p"$MYSQL_ROOT_PASSWORD" gs_auth -e \
    "INSERT INTO auditoria_eventos (timestamp, usuario, tipo, accion, entidad, detalle)
     VALUES ('$ahora', 'sistema', 'BACKUP', '$accion', 'Backup diario', '$detalle_escapado');" \
    2>/dev/null \
    || echo "[$(date)] ADVERTENCIA: no se pudo registrar el backup en auditoria (¿ms-auth/mysql no tiene aun la tabla creada?)"
}

on_error() {
  registrar_auditoria "Backup fallido" "Fallo durante el backup $FECHA — ver \`docker compose logs backup\` para el detalle"
}
trap on_error ERR

set -e
echo "[$(date)] === Iniciando backup $FECHA ==="
mkdir -p "$TMP_DIR"

for DB in $BASES; do
  echo "[$(date)] Dump de $DB..."
  mysqldump -h mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
    --single-transaction --routines --triggers "$DB" \
    | gzip > "$TMP_DIR/$DB.sql.gz"
done

echo "[$(date)] Empaquetando archivos de MinIO (escaneos/comprobantes)..."
tar czf "$TMP_DIR/minio-data.tar.gz" -C /minio_data .

echo "[$(date)] Subiendo a Google Drive ($REMOTE)..."
rclone copy "$TMP_DIR" "$REMOTE" --create-empty-src-dirs

echo "[$(date)] Backup subido OK. Limpiando temporales locales..."
rm -rf "$TMP_DIR"

registrar_auditoria "Backup completado" "5 bases (auth, catalogo, pedidos, finanzas, stock) + archivos de MinIO subidos a Google Drive"
echo "[$(date)] === Backup $FECHA completado ==="
