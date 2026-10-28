import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Mini servidor HTTP del servicio de backup: dispara el backup a demanda y deja ver y bajar las copias
 * (la pantalla Configuración → Copias de seguridad).
 *
 * <p>El backup ya corre solo a las 3 AM por cron (ver crontab). Este servidor agrega:
 * <ul>
 *   <li>{@code POST /run}: lanza el MISMO backup.sh en segundo plano.</li>
 *   <li>{@code GET /estado}: qué copias hay, a dónde llegó cada una, si hay una en curso y si Google Drive
 *       está configurado.</li>
 *   <li>{@code GET /descargar?copia=AAAA-MM-DD_HH-MM}: la copia completa en un .tar, en streaming.</li>
 * </ul></p>
 *
 * <p>Seguridad: solo escucha en la red interna de Docker (gs-net) — NO se
 * publica al host — y además exige el header X-Backup-Key ==
 * env BACKUP_TRIGGER_KEY. El único que lo llama es el backend (server-to-server),
 * que valida antes que el usuario sea ADMIN. La key nunca llega al navegador. El nombre de la copia se
 * valida contra un patrón fijo antes de tocar el disco, así no se puede pedir otra ruta.</p>
 *
 * <p>Sin frameworks: {@code com.sun.net.httpserver.HttpServer} (parte del JDK)
 * alcanza de sobra. Se compila con {@code javac} directo,
 * sin Maven — no necesita ninguna dependencia externa.</p>
 */
public class TriggerServer {

    private static final String BACKUP_SCRIPT = entorno("BACKUP_SCRIPT", "/backup.sh");
    private static final String BACKUP_LOG = entorno("BACKUP_LOG", "/var/log/backup.log");
    private static final Path COPIAS = Paths.get(entorno("BACKUP_DIR_LOCAL", "/backups/local"));
    private static final Path TEMPORAL = Paths.get(entorno("BACKUP_DIR_TMP", "/backups/tmp"));
    private static final Path RCLONE_CONF = Paths.get(entorno("RCLONE_CONFIG", "/root/.config/rclone/rclone.conf"));

    /** Así se llama la carpeta de cada copia (la arma backup.sh con date +%Y-%m-%d_%H-%M). */
    private static final Pattern NOMBRE_COPIA = Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}");
    private static final Set<String> DESTINOS = Set.of("local", "drive", "drive-fallo");
    /** Una carpeta temporal más vieja que esto es un resto de un backup cortado, no uno en curso. */
    private static final Duration TEMPORAL_VIGENTE = Duration.ofMinutes(60);

    // Evita backups solapados: si ya hay uno corriendo, no lanza otro.
    private static final AtomicBoolean corriendo = new AtomicBoolean(false);

    private static String key;

    private record Copia(String nombre, long bytes, String destino) {}

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(entorno("BACKUP_TRIGGER_PORT", "3002"));
        key = entorno("BACKUP_TRIGGER_KEY", "");

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", TriggerServer::health);
        server.createContext("/run", TriggerServer::run);
        server.createContext("/estado", TriggerServer::estado);
        server.createContext("/descargar", TriggerServer::descargar);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("[trigger] Servidor de backup escuchando en :" + port);
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
        if (!autorizado(ex)) {
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

    private static void estado(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            responder(ex, 404, "{\"error\":\"not found\"}");
            return;
        }
        if (!autorizado(ex)) {
            responder(ex, 401, "{\"error\":\"clave invalida\"}");
            return;
        }

        boolean enCurso = corriendo.get() || hayBackupEnCurso();
        StringBuilder json = new StringBuilder("{\"enCurso\":").append(enCurso)
                .append(",\"driveConfigurado\":").append(driveConfigurado())
                .append(",\"copias\":[");
        boolean primera = true;
        for (Copia c : listarCopias(enCurso)) {
            if (!primera) json.append(',');
            primera = false;
            json.append("{\"nombre\":\"").append(c.nombre())
                    .append("\",\"bytes\":").append(c.bytes())
                    .append(",\"destino\":\"").append(c.destino()).append("\"}");
        }
        json.append("]}");
        responder(ex, 200, json.toString());
    }

    private static void descargar(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            responder(ex, 404, "{\"error\":\"not found\"}");
            return;
        }
        if (!autorizado(ex)) {
            responder(ex, 401, "{\"error\":\"clave invalida\"}");
            return;
        }

        String nombre = parametro(ex.getRequestURI().getRawQuery(), "copia");
        if (nombre == null || !NOMBRE_COPIA.matcher(nombre).matches()) {
            responder(ex, 400, "{\"error\":\"nombre de copia invalido\"}");
            return;
        }
        if (!Files.isDirectory(COPIAS.resolve(nombre))) {
            responder(ex, 404, "{\"error\":\"no existe esa copia\"}");
            return;
        }

        // Sin comprimir: los archivos de adentro ya son .gz, comprimir de nuevo solo gasta CPU.
        Process tar = new ProcessBuilder("tar", "cf", "-", "-C", COPIAS.toString(), nombre)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try (InputStream in = tar.getInputStream()) {
            ex.getResponseHeaders().set("Content-Type", "application/x-tar");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                in.transferTo(os);
            }
            if (tar.waitFor() != 0) {
                System.err.println("[trigger] tar terminó con error armando la copia " + nombre);
            }
        } catch (IOException e) {
            // El navegador cortó la descarga: no tiene sentido seguir leyendo el disco.
            System.err.println("[trigger] Descarga de " + nombre + " interrumpida: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            tar.destroy();
        }
    }

    /** Las copias de {@code COPIAS}, de la más nueva a la más vieja. Las que todavía se están copiando no salen. */
    private static List<Copia> listarCopias(boolean enCurso) throws IOException {
        List<Copia> copias = new ArrayList<>();
        if (!Files.isDirectory(COPIAS)) return copias;
        List<Path> carpetas;
        try (Stream<Path> s = Files.list(COPIAS)) {
            carpetas = s.filter(Files::isDirectory)
                    .filter(p -> NOMBRE_COPIA.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        }
        for (Path dir : carpetas) {
            String destino = leerDestino(dir);
            // Sin la marca de backup.sh, la copia es de antes de que existiera (se muestra) o es la que se está
            // moviendo en este momento desde el volumen temporal (no se muestra: está incompleta).
            if (destino == null && enCurso) continue;
            copias.add(new Copia(dir.getFileName().toString(), tamanio(dir), destino == null ? "desconocido" : destino));
        }
        return copias;
    }

    private static String leerDestino(Path dir) {
        try {
            String valor = Files.readString(dir.resolve("destino.txt"), StandardCharsets.UTF_8).trim();
            return DESTINOS.contains(valor) ? valor : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static long tamanio(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    /** backup.sh arma cada copia en una carpeta temporal y la borra al terminar (o si falla). */
    private static boolean hayBackupEnCurso() {
        if (!Files.isDirectory(TEMPORAL)) return false;
        long limite = System.currentTimeMillis() - TEMPORAL_VIGENTE.toMillis();
        try (Stream<Path> s = Files.list(TEMPORAL)) {
            return s.filter(Files::isDirectory).anyMatch(p -> p.toFile().lastModified() > limite);
        } catch (IOException e) {
            return false;
        }
    }

    /** backup.sh sube a Drive solo si rclone tiene un remoto llamado exactamente "gdrive". */
    private static boolean driveConfigurado() {
        try {
            return Files.readAllLines(RCLONE_CONF, StandardCharsets.UTF_8).stream()
                    .anyMatch(l -> l.strip().equals("[gdrive]"));
        } catch (IOException e) {
            return false;
        }
    }

    private static String parametro(String query, String nombre) {
        if (query == null) return null;
        for (String par : query.split("&")) {
            int i = par.indexOf('=');
            if (i > 0 && par.substring(0, i).equals(nombre)) {
                return URLDecoder.decode(par.substring(i + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** Sin key configurada, o key que no coincide = no se permite por HTTP (solo cron). */
    private static boolean autorizado(HttpExchange ex) {
        String recibida = ex.getRequestHeaders().getFirst("X-Backup-Key");
        return !key.isBlank() && claveValida(recibida);
    }

    /** Comparación en tiempo constante para no filtrar la key por timing. */
    private static boolean claveValida(String recibida) {
        if (recibida == null) return false;
        return MessageDigest.isEqual(
                key.getBytes(StandardCharsets.UTF_8),
                recibida.getBytes(StandardCharsets.UTF_8));
    }

    private static String entorno(String nombre, String porDefecto) {
        return System.getenv().getOrDefault(nombre, porDefecto);
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
