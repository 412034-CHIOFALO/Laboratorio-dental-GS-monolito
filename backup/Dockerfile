FROM debian:bookworm-slim

# Alpine's mysql-client (musl) no trae el plugin caching_sha2_password.so que
# usa MySQL 8 por defecto, y mysqldump falla al conectar. El cliente MariaDB
# de Debian sí lo incluye.
ENV TZ=America/Argentina/Cordoba

# JDK 17 (mismo que el resto del proyecto) para el mini-servidor de disparo del
# backup a demanda — TriggerServer.java, sin Maven ni dependencias: se compila
# acá mismo con javac (usa solo com.sun.net.httpserver, parte del JDK).
RUN apt-get update && apt-get install -y --no-install-recommends \
      bash default-mysql-client rclone cron tzdata ca-certificates openjdk-17-jdk-headless \
    && ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone \
    && rm -rf /var/lib/apt/lists/*

COPY backup.sh /backup.sh
COPY entrypoint.sh /entrypoint.sh
COPY TriggerServer.java /TriggerServer.java
COPY crontab /etc/cron.d/backup-cron
RUN chmod +x /backup.sh /entrypoint.sh && chmod 0644 /etc/cron.d/backup-cron \
    && javac -encoding UTF-8 /TriggerServer.java -d /

# El entrypoint vuelca el entorno del contenedor a /etc/cron-env (cron no lo
# hereda) y después arranca cron en foreground.
CMD ["/entrypoint.sh"]
