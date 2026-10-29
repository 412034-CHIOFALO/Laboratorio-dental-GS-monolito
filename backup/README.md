# Backup automático (local siempre, Google Drive si lo configurás)

Se genera un dump comprimido de la base (`gs_auth`, donde viven TODAS las tablas de la app) y un
`.tar.gz` de los archivos de MinIO (escaneos 3D y comprobantes). Siempre queda una **copia local**
en `./backups/<fecha>/` del servidor (se conservan las últimas 14), y si hay un Google Drive
configurado también se sube a `gs-backups/<fecha>/` en esa cuenta (también las últimas 14: las más
viejas se borran de Drive, que gratis tiene 15 GB).

**Cuándo corre:** todos los días a las 3:00, a las 13:30 *solo si no hay un backup de las últimas
20 horas* y al arrancar el contenedor con la misma condición. Esto último importa porque el servidor
no está prendido 24/7: si a las 3 AM estaba apagado, se pone al día cuando se prende.

**Cada backup se verifica** (los `.gz` descomprimen y cada dump termina con `Dump completed`);
si algo falla queda registrado como *Backup fallido* en la pantalla de Auditoría. Cada copia lleva un
`destino.txt` que dice a dónde llegó (`local`, `drive` o `drive-fallo`).

## Desde el panel (sin tocar el servidor)

**Configuración → Copias de seguridad** (solo administrador):

- **Hacer backup ahora**: el mismo backup de las 3:00. Avisa cuando termina y si llegó a Drive.
- **Lista de copias** con fecha, tamaño y a dónde llegó cada una.
- **Descargar**: baja la copia completa a la computadora desde la que estás usando el panel (un `.tar`
  con el dump y el archivo de MinIO). Cada descarga queda en Auditoría.
- Si Google Drive está conectado o no.

Una copia descargada tiene todos los datos del laboratorio: guardarla en un lugar seguro, no mandarla
por mail ni por WhatsApp. Para restaurarla, `tar xf gs-backup-<fecha>.tar` y seguir con "Restaurar un
backup de verdad".

## Probar que se puede restaurar

Un backup que nunca se restauró no está probado. Este script levanta un MySQL **descartable** (no toca
el de verdad), restaura el backup local más nuevo y muestra cuántas filas tiene cada tabla:
```
./backup/probar-restauracion.sh
```

## Google Drive (opcional, una sola vez, unos 15 minutos)

Hace falta una autorización de Google que se da **desde una computadora con navegador** (no desde el
servidor). Después el servidor la renueva solo. Los nombres de los menús de Google Cloud cambian de vez en
cuando; si algo no está donde dice, buscá el equivalente.

### 1. En Google Cloud (con la cuenta de Gmail del laboratorio)

El cliente compartido de rclone está en retiro durante 2026, por eso se crea uno propio (es gratis):

1. Entrá a <https://console.cloud.google.com/> y creá un proyecto (por ejemplo `gs-backup`).
2. **APIs y servicios → Biblioteca** → buscá **Google Drive API** → **Habilitar**.
3. **Pantalla de consentimiento de OAuth** (en la consola nueva: *Google Auth Platform*): tipo
   **Externo**, nombre de la app `GS Backup`, y el mail del laboratorio como contacto.
4. **Publicá la app (pasarla a «En producción»).** Es lo más importante: si queda en «Testing», la
   autorización **vence a los 7 días** y el backup deja de subirse a Drive sin avisar (en el panel las
   copias pasan a decir «Falló Drive»). Para uso propio no pide verificación; solo muestra un cartel de
   «app no verificada» al autorizar. Para habilitar **Publicar app**, completá antes *Información de la
   marca*: correo de asistencia, **página principal** (`https://gsortodoncia.duckdns.org`) y **política de
   privacidad** (`https://gsortodoncia.duckdns.org/privacidad`, página pública del sistema).
5. **Credenciales → Crear credenciales → ID de cliente de OAuth → Aplicación de escritorio.** Copiá
   el **ID de cliente** y el **Secreto**.

### 2. En tu computadora

Instalá rclone (`winget install Rclone.Rclone`, y abrí una terminal nueva) y creá el remoto:

```
rclone config
```

- `n` (New remote) · nombre: **`gdrive`** (exactamente así, el script lo usa)
- Storage: `drive` (Google Drive)
- `client_id` y `client_secret`: los del paso anterior
- `scope`: **`drive.file`** («rclone solo puede ver y modificar los archivos que él mismo creó»).
  Así, aunque alguien se llevara el token del servidor, no vería el resto del Drive del laboratorio.
- `service_account_file`: Enter · *Edit advanced config*: `n`
- *Use web browser to automatically authenticate*: **`y`**. Se abre el navegador: entrá con la cuenta del
  laboratorio y aceptá (si dice «app no verificada»: *Avanzado → Ir a GS Backup*).
- *Configure as Shared Drive*: `n` · confirmá con `y` · `q`

### 3. Llevarlo al servidor

`rclone config file` muestra dónde quedó el `rclone.conf`. Copialo al servidor (la carpeta se monta
como *directorio*: rclone reescribe el archivo al renovar el token y, si estuviera montado como archivo
suelto, falla con «device or resource busy»):

```
ssh -i ~/.ssh/gs_deploy nico@192.168.1.23 "mkdir -p /opt/gs-monolito/backup/rclone-config"
scp -i ~/.ssh/gs_deploy "<ruta que mostró rclone config file>" nico@192.168.1.23:/opt/gs-monolito/backup/rclone-config/rclone.conf
ssh -i ~/.ssh/gs_deploy nico@192.168.1.23 "chmod 600 /opt/gs-monolito/backup/rclone-config/rclone.conf"
```

`rclone.conf` tiene el token de acceso: está en `.gitignore`, nunca se commitea. Cuando termine, borrá el
remoto de tu computadora (`rclone config delete gdrive`) para que el token quede en un solo lugar.

### 4. Probar

No hace falta reiniciar nada: el script lee la configuración en cada backup.

```
docker compose exec backup rclone lsd gdrive:
docker compose exec backup /backup.sh
```

Tiene que decir «Subiendo a Google Drive» y, en el panel, la copia nueva pasa a «Servidor + Drive».
Si dice «Falló Drive», mirá `docker compose logs backup`: lo más común es la app de Google en «Testing»
(volver al paso 1.4 y repetir el paso 2).

## Probar manualmente (sin esperar a las 3 AM)

Desde el panel, o:
```
docker compose exec backup /backup.sh
```
(Si ya hay una copia de ese mismo minuto, no hace otra.)

## Restaurar un backup de verdad (con el sistema parado)

```
docker compose stop app
gunzip < backups/<fecha>/gs_auth.sql.gz | docker exec -i gs-monolito-mysql-1 mysql -uroot -p<clave> gs_auth
docker compose start app
```

Los archivos de MinIO se restauran descomprimiendo `minio-data.tar.gz` directo en el volumen
`gs-monolito_minio_data`.
