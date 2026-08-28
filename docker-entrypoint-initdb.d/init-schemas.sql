-- Un único DataSource sirve a los 5 schemas del monolito (uno por dominio,
-- via @Table(schema = "gs_xxx") en cada @Entity). A diferencia de los
-- microservicios originales, acá ninguno se auto-crea solo con
-- createDatabaseIfNotExist=true (esa opción solo crea el schema de la URL de
-- conexión) — por eso hace falta crearlos todos antes de que Hibernate corra.
CREATE DATABASE IF NOT EXISTS gs_auth;
CREATE DATABASE IF NOT EXISTS gs_catalogo;
CREATE DATABASE IF NOT EXISTS gs_pedidos;
CREATE DATABASE IF NOT EXISTS gs_finanzas;
CREATE DATABASE IF NOT EXISTS gs_stock;
