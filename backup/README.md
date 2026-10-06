# Backup automático (local siempre, Google Drive si lo configurás)

Se genera un dump comprimido de la base (`gs_auth`, donde viven TODAS las tablas de la app) y un
`.tar.gz` de los archivos de MinIO (escaneos 3D y comprobantes). Siempre queda una **copia local**
en `./backups/<fecha>/` del servidor (se conservan los últimos 14), y si hay un Google Drive
configurado también se sube a `gs-backups/<fecha>/` en esa cuenta.

**Cuándo corre:** todos los días a las 3:00, a las 13:30 *solo si no hay un backup de las últimas
20 horas* y al arrancar el contenedor con la misma condición. Esto último importa porque el servidor
no está prendido 24/7: si a las 3 AM estaba apagado, se pone al día cuando se prende.

**Cada backup se verifica** (los `.gz` descomprimen y cada dump termina con `Dump completed`);
si algo falla queda registrado como *Backup fallido* en la pantalla de Auditoría.

> Antes de este cambio el backup dependía de Google Drive y, como nunca se configuró, jamás generó
> un solo archivo. Si todavía no hay `./backups`, el primero se hace solo a los pocos minutos de
> levantar el servicio, o a mano con `docker compose exec backup /backup.sh`.

## Probar que se puede restaurar

Un backup que nunca se restauró no está probado. Este script levanta un MySQL **descartable** (no toca
el de verdad), restaura el backup local más nuevo y muestra cuántas filas tiene cada tabla:
```
./backup/probar-restauracion.sh
```

## Google Drive (opcional, una sola vez)

1. Necesitás la cuenta de Gmail del laboratorio ya creada (con verificación
   en 2 pasos activada — la pide `rclone` para el login).

2. **Antes de levantar el servicio por primera vez**, creá la carpeta en el
   servidor (se monta el *directorio*, no el archivo directo — si montás el
   archivo solo, rclone no puede reescribir su config al refrescar el token
   OAuth y tira "device or resource busy"):
   ```
   mkdir -p backup/rclone-config
   ```

3. En el servidor, corré esto para autorizar el acceso a Drive (la carpeta
   `backup/rclone-config` del host queda montada ahí adentro, así que lo que
   `rclone` guarde queda directo en tu carpeta, sin pasos extra):
   ```
   docker compose run --rm backup rclone config
   ```
   Seguí el asistente:
   - `n` (New remote)
   - Nombre: **`gdrive`** (tiene que ser exactamente así, el script lo usa)
   - Tipo: buscá `drive` (Google Drive) en la lista
   - Client ID / Secret: dejalos en blanco (Enter)
   - Scope: `1` (acceso completo a Drive)
   - Root folder ID: en blanco
   - Service account: en blanco
   - Edit advanced config: `n`
   - Use auto config: si el servidor no tiene navegador (lo normal), decís
     `n` — te da un link para abrir en TU compu, lo abrís logueado con la
     cuenta del laboratorio, autorizás, y pegás el código que te da de
     vuelta en la terminal del servidor.
   - Configure as team drive: `n`
   - Confirmá con `y`

   `backup/rclone-config/rclone.conf` queda con el token de acceso a tu
   Drive — está en `.gitignore`, nunca se commitea.

4. Levantá el servicio (ya queda corriendo con el cron adentro):
   ```
   docker compose up -d backup
   ```

## Probar manualmente (sin esperar a las 3 AM)

```
docker compose exec backup /backup.sh
```

## Restaurar un backup de verdad (con el sistema parado)

```
docker compose stop app
gunzip < backups/<fecha>/gs_auth.sql.gz | docker exec -i gs-monolito-mysql-1 mysql -uroot -p<clave> gs_auth
docker compose start app
```

Los archivos de MinIO se restauran descomprimiendo `minio-data.tar.gz` directo en el volumen
`gs-monolito_minio_data`.
