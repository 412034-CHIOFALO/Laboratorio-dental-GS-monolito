# Un único DataSource sirve a los 5 schemas del monolito (uno por dominio,
# via @Table(schema = "gs_xxx") en cada @Entity). A diferencia de los
# microservicios originales, acá ninguno se auto-crea solo con
# createDatabaseIfNotExist=true (esa opción solo crea el schema de la URL de
# conexión) — por eso hace falta crearlos todos antes de que Hibernate corra.
#
# Además crea el usuario de aplicación (GS_APP_DB_USER/GS_APP_DB_PASSWORD,
# ver docker-compose.yml) con privilegios acotados SOLO a estos 5 schemas —
# la app nunca se conecta como root. root queda para tareas de administración
# (este script) y para el backup (mysqldump necesita poder leer las 5 bases).
#
# Se sourcea (no es ejecutable) dentro del entrypoint oficial de la imagen
# mysql, que ya corre con "set -e" — cualquier error acá aborta el arranque.

if [ -z "${GS_APP_DB_PASSWORD:-}" ]; then
	echo >&2 "init-schemas.sh: falta GS_APP_DB_PASSWORD, no se puede crear el usuario de aplicación"
	exit 1
fi

APP_USER="${GS_APP_DB_USER:-gs_app}"

mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<EOSQL
CREATE DATABASE IF NOT EXISTS gs_auth;
CREATE DATABASE IF NOT EXISTS gs_catalogo;
CREATE DATABASE IF NOT EXISTS gs_pedidos;
CREATE DATABASE IF NOT EXISTS gs_finanzas;
CREATE DATABASE IF NOT EXISTS gs_stock;

CREATE USER IF NOT EXISTS '${APP_USER}'@'%' IDENTIFIED BY '${GS_APP_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON gs_auth.*     TO '${APP_USER}'@'%';
GRANT ALL PRIVILEGES ON gs_catalogo.* TO '${APP_USER}'@'%';
GRANT ALL PRIVILEGES ON gs_pedidos.*  TO '${APP_USER}'@'%';
GRANT ALL PRIVILEGES ON gs_finanzas.* TO '${APP_USER}'@'%';
GRANT ALL PRIVILEGES ON gs_stock.*    TO '${APP_USER}'@'%';
FLUSH PRIVILEGES;
EOSQL
