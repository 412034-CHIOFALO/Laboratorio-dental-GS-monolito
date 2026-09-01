import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mini servidor HTTP para disparar el backup a demanda (el botón de la UI).
 *
 * <p>El backup ya corre solo a las 3 AM por cron (ver crontab). Este servidor
 * agrega la posibilidad de correrlo AHORA sin entrar por consola: expone
 * POST /run, que lanza el MISMO backup.sh en segundo plano.</p>
 *
 * <p>Seguridad: solo escucha en la red interna de Docker (gs-net) — NO se
 * publica al host — y además exige el header X-Backup-Key ==
 * env BACKUP_TRIGGER_KEY. El único que lo llama es ms-auth (server-to-server),
 * que valida antes que el usuario sea ADMIN. La key nunca llega al navegador.</p>
 *
 * <p>Sin frameworks: {@code com.sun.net.httpserver.HttpServer} (parte del JDK)
 * alcanza de sobra para dos endpoints. Se compila con {@code javac} directo,
 * sin Maven — no necesita ninguna dependencia externa.</p>
 */
public class TriggerServer {

    private static final String BACKUP_SCRIPT = "/backup.sh";
    private static final String BACKUP_LOG = "/var/log/backup.log";

    // Evita backups solapados: si ya hay uno corriendo, no lanza otro.
    private static final AtomicBoolean corriendo = new AtomicBoolean(false);

    private static String key;

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("BACKUP_TRIGGER_PORT", "3002"));
        key = System.getenv().getOrDefault("BACKUP_TRIGGER_KEY", "");

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", TriggerServer::health);
        server.createContext("/run", TriggerServer::run);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("[trigger] Servidor de disparo de backup escuchando en :" + port);
    }

    private static void health(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            responder(ex, 404, "{\"error\":\"not found\"}");
            return;
        }
        responder(ex, 200, "{\"ok\":true}");
    }

    private static void run(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            responder(ex, 404, "{\"error\":\"not found\"}");
            return;
        }

        // Sin key configurada, o key que no coincide = no se permite disparar por HTTP (solo cron).
        String recibida = ex.getRequestHeaders().getFirst("X-Backup-Key");
        if (key.isBlank() || !claveValida(recibida)) {
            responder(ex, 401, "{\"error\":\"clave invalida\"}");
            return;
        }

        if (!corriendo.compareAndSet(false, true)) {
            responder(ex, 409, "{\"error\":\"ya hay un backup en curso\"}");
            return;
        }

        new Thread(() -> {
            try {
                // Mismo destino que usa cron (crontab: >> /var/log/backup.log 2>&1),
                // así el log queda igual sin importar cómo se disparó el backup.
                ProcessBuilder pb = new ProcessBuilder("/bin/bash", BACKUP_SCRIPT);
                pb.redirectErrorStream(true);
                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(BACKUP_LOG)));
                pb.start().waitFor();
            } catch (Exception e) {
                System.err.println("[trigger] Error corriendo el backup: " + e.getMessage());
            } finally {
                corriendo.set(false);
            }
        }, "backup-trigger").start();

        responder(ex, 202, "{\"ok\":true,\"mensaje\":\"Backup iniciado en segundo plano\"}");
    }

    /** Comparación en tiempo constante para no filtrar la key por timing. */
    private static boolean claveValida(String recibida) {
        if (recibida == null) return false;
        return MessageDigest.isEqual(
                key.getBytes(StandardCharsets.UTF_8),
                recibida.getBytes(StandardCharsets.UTF_8));
    }

    private static void responder(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
