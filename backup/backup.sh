#!/bin/bash
# Backup de la base y de los archivos de MinIO.
#
#   /backup.sh                  hace el backup ahora
#   /backup.sh --si-hace-falta  lo hace solo si no hay uno de las últimas BACKUP_HORAS_ENTRE horas
#                               (lo usa el cron del mediodía y el arranque: el servidor no está 24/7,
#                               así que "a las 3 AM" no alcanza — si estaba apagado, se pone al día)
#
# Siempre guarda una copia LOCAL en /backups/local (en el servidor: ./backups, ver docker-compose.yml)
# y, si hay un Google Drive configurado en rclone (remoto "gdrive"), también la sube. Antes todo
# dependía de Drive: sin Drive el script fallaba al subir y borraba lo que había hecho, y en la
# práctica nunca se generó ni un backup.
set -uo pipefail

# Desde cron el entorno viene de /etc/cron-env; a mano, lo cargamos si falta.
if [ -z "${MYSQL_ROOT_PASSWORD:-}" ] && [ -f /etc/cron-env ]; then
  . /etc/cron-env
fi

FECHA=$(date +%Y-%m-%d_%H-%M)
LOCAL_DIR="${BACKUP_DIR_LOCAL:-/backups/local}"
TMP_DIR="/backups/tmp/$FECHA"
# Las tablas reales de la app viven TODAS en gs_auth (ver V4 de Flyway). Los otros schemas
# (gs_pedidos, gs_stock, ...) son restos vacíos del diseño en microservicios.
BASES="${BACKUP_BASES:-gs_auth}"
RETENCION="${BACKUP_RETENCION:-14}"
HORAS_ENTRE="${BACKUP_HORAS_ENTRE:-20}"
REMOTE="gdrive:gs-backups/$FECHA"

if [ "${1:-}" = "--si-hace-falta" ]; then
  reciente=$(find "$LOCAL_DIR" -mindepth 1 -maxdepth 1 -type d -mmin -$((HORAS_ENTRE * 60)) 2>/dev/null | head -1)
  if [ -n "$reciente" ]; then
    echo "[$(date)] Ya hay un backup de las últimas ${HORAS_ENTRE} h ($(basename "$reciente")): no hace falta otro."
    exit 0
  fi
fi

# Deja un registro en la tabla de auditoría para que el backup aparezca en la pantalla de Auditoría
# del dashboard. Por SQL directo (no HTTP) para no depender de que la app esté arriba.
registrar_auditoria() {
  local accion="$1" detalle="$2"
  local ahora detalle_escapado
  ahora=$(date '+%Y-%m-%d %H:%M:%S')
  detalle_escapado=$(printf '%s' "$detalle" | sed "s/'/''/g")
  mysql -h mysql -uroot -p"$MYSQL_ROOT_PASSWORD" gs_auth -e \
    "INSERT INTO auditoria_eventos (timestamp, usuario, tipo, accion, entidad, detalle)
     VALUES ('$ahora', 'sistema', 'BACKUP', '$accion', 'Backup', '$detalle_escapado');" \
    2>/dev/null \
    || echo "[$(date)] ADVERTENCIA: no se pudo registrar el backup en auditoría."
}

on_error() {
  registrar_auditoria "Backup fallido" "Fallo durante el backup $FECHA — ver \`docker compose logs backup\` para el detalle"
  rm -rf "$TMP_DIR"
}
trap on_error ERR

set -e
echo "[$(date)] === Iniciando backup $FECHA ==="
mkdir -p "$TMP_DIR" "$LOCAL_DIR"

for DB in $BASES; do
  echo "[$(date)] Dump de $DB..."
  mysqldump -h mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
    --single-transaction --routines --triggers "$DB" \
    | gzip > "$TMP_DIR/$DB.sql.gz"
done

echo "[$(date)] Empaquetando archivos de MinIO (escaneos/comprobantes)..."
tar czf "$TMP_DIR/minio-data.tar.gz" -C /minio_data .

# Un backup que no se puede leer no es un backup: se comprueba que cada archivo descomprima y que
# cada dump esté COMPLETO (mysqldump escribe "Dump completed" al final solo si no se cortó).
echo "[$(date)] Verificando los archivos..."
for f in "$TMP_DIR"/*.gz; do
  gzip -t "$f"
done
for f in "$TMP_DIR"/*.sql.gz; do
  zcat "$f" | tail -n 3 | grep -q "Dump completed" || { echo "El dump $f está incompleto"; false; }
done

DESTINO="$LOCAL_DIR/$FECHA"
mv "$TMP_DIR" "$DESTINO"
TAM=$(du -sh "$DESTINO" | cut -f1)
echo "[$(date)] Copia local guardada en $DESTINO ($TAM)."

DONDE="solo local ($DESTINO, $TAM)"
if rclone listremotes 2>/dev/null | grep -q '^gdrive:'; then
  echo "[$(date)] Subiendo a Google Drive ($REMOTE)..."
  if rclone copy "$DESTINO" "$REMOTE" --create-empty-src-dirs; then
    DONDE="local ($TAM) + Google Drive ($REMOTE)"
  else
    DONDE="solo local ($TAM): FALLÓ la subida a Google Drive"
    echo "[$(date)] ADVERTENCIA: la subida a Drive falló; queda la copia local."
  fi
else
  echo "[$(date)] Google Drive no está configurado (falta el remoto 'gdrive' de rclone): backup solo local."
fi

# Retención: se conservan los últimos $RETENCION backups locales.
ls -1dt "$LOCAL_DIR"/*/ 2>/dev/null | tail -n +$((RETENCION + 1)) | while read -r viejo; do
  echo "[$(date)] Borrando backup local viejo: $viejo"
  rm -rf "$viejo"
done

registrar_auditoria "Backup completado" "Bases ($BASES) + archivos de MinIO: $DONDE"
echo "[$(date)] === Backup $FECHA completado: $DONDE ==="
