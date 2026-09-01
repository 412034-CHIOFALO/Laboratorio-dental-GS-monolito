# Backup automático → Google Drive

Todos los días a las 3 AM se genera un dump comprimido de las 5 bases (auth,
catálogo, pedidos, finanzas, stock) + un `.tar.gz` de los archivos de MinIO
(escaneos 3D y comprobantes), y se suben a una carpeta `gs-backups/<fecha>/`
en el Google Drive de la cuenta del laboratorio.

## Configuración (una sola vez)

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

## Restaurar un backup

```
gunzip < gs_pedidos.sql.gz | docker exec -i gs-mysql mysql -uroot -p<pass> gs_pedidos
```

Los archivos de MinIO se restauran descomprimiendo `minio-data.tar.gz`
directo en el volumen `gs_minio_data`.
