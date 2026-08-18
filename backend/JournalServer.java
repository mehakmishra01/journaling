import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dependency-free HTTP server for the Personal Journal app. Serves the static
 * frontend and a small JSON API backed by a CSV file. No frameworks used.
 */
public class JournalServer {

    private static final String[] REFLECTIONS = {
        "What was one thing that made you happy today?",
        "What did you learn today?",
        "What are you grateful for right now?",
        "What challenged you today, and how did you handle it?",
        "Describe a small win from today.",
        "What is something you want to remember about today?",
        "How are you really feeling, and why?",
        "What would make tomorrow great?"
    };

    private final JournalManager manager;
    private final Path frontendDir;

    public JournalServer(JournalManager manager, Path frontendDir) {
        this.manager = manager;
        this.frontendDir = frontendDir;
    }

    public static void main(String[] args) throws IOException {
        Path root = findProjectRoot();
        Path frontendDir = root.resolve("frontend");
        Path csvPath = root.resolve("data").resolve("journal.csv");

        JournalManager manager = new JournalManager(csvPath);
        manager.init();

        // Preferred port from PORT env (or 8080), then a few fallbacks in case the
        // preferred one is taken or reserved by the OS (common on Windows). 0 lets
        // the OS pick any free port as a last resort.
        List<Integer> candidates = new ArrayList<>();
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            try { candidates.add(Integer.parseInt(envPort.trim())); } catch (NumberFormatException ignored) {}
        }
        for (int p : new int[] { 8080, 8090, 7777, 3000, 5050, 0 }) {
            if (!candidates.contains(p)) candidates.add(p);
        }

        JournalServer app = new JournalServer(manager, frontendDir);
        HttpServer server = null;
        for (int candidate : candidates) {
            try {
                server = HttpServer.create(new InetSocketAddress("0.0.0.0", candidate), 0);
                break;
            } catch (IOException ex) {
                System.out.println("Port " + candidate + " unavailable (" + ex.getMessage() + "), trying next...");
            }
        }
        if (server == null) {
            System.err.println("Could not bind to any port. Set the PORT environment variable to a free port and retry.");
            return;
        }

        server.createContext("/", app::handle);
        server.setExecutor(null);
        server.start();

        int port = server.getAddress().getPort();
        System.out.println("Personal Journal running at http://localhost:" + port);
        System.out.println("Data file: " + csvPath.toAbsolutePath());
        System.out.println("Press Ctrl+C to stop.");
    }

    /** Walk up from the working directory to find the folder containing 'frontend'. */
    private static Path findProjectRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve("frontend"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return Path.of("").toAbsolutePath();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if (path.startsWith("/api/")) {
                handleApi(exchange, method, path);
            } else if (method.equals("GET")) {
                serveStatic(exchange, path);
            } else {
                sendText(exchange, 405, "Method Not Allowed");
            }
        } catch (Exception e) {
            e.printStackTrace();
            try {
                sendJson(exchange, 500, "{\"error\":" + Json.quote(String.valueOf(e.getMessage())) + "}");
            } catch (IOException ignored) {}
        } finally {
            exchange.close();
        }
    }

    // ---------------------------------------------------------------- API ----

    private void handleApi(HttpExchange exchange, String method, String path) throws IOException {
        if (path.equals("/api/entries")) {
            switch (method) {
                case "GET": handleGetEntries(exchange); return;
                case "POST": handleCreateEntry(exchange); return;
                default: sendText(exchange, 405, "Method Not Allowed"); return;
            }
        }

        if (path.startsWith("/api/entries/")) {
            switch (method) {
                case "DELETE": handleDeleteEntry(exchange, path); return;
                case "PUT": handleUpdateEntry(exchange, path); return;
                default: sendText(exchange, 405, "Method Not Allowed"); return;
            }
        }

        if (path.equals("/api/stats") && method.equals("GET")) {
            sendJson(exchange, 200, manager.stats().toJson());
            return;
        }

        if (path.equals("/api/reflection") && method.equals("GET")) {
            int idx = (int) (LocalDate.now().toEpochDay() % REFLECTIONS.length);
            if (idx < 0) idx += REFLECTIONS.length;
            String body = "{\"question\":" + Json.quote(REFLECTIONS[idx]) + "}";
            sendJson(exchange, 200, body);
            return;
        }

        sendJson(exchange, 404, "{\"error\":\"Not found\"}");
    }

    private void handleGetEntries(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String search = query.get("search");
        String mood = query.get("mood");

        List<JournalEntry> entries;
        if ((search != null && !search.isBlank()) || (mood != null && !mood.isBlank())) {
            entries = manager.search(search, mood);
        } else {
            entries = manager.getAll();
        }
        // most recent first (by id, which increases over time)
        entries.sort((a, b) -> Integer.compare(b.getId(), a.getId()));
        sendJson(exchange, 200, entriesToJson(entries));
    }

    private void handleCreateEntry(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> data = Json.parseObject(body);

        String title = data.getOrDefault("title", "").trim();
        String entryText = data.getOrDefault("entry", "").trim();
        String mood = data.getOrDefault("mood", "").trim();
        String date = data.getOrDefault("date", "").trim();
        String image = data.getOrDefault("image", "").trim();

        if (title.isEmpty() && entryText.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"Title or entry text is required\"}");
            return;
        }

        JournalEntry created = manager.add(date, mood, title, entryText, image);
        sendJson(exchange, 201, created.toJson());
    }

    private void handleUpdateEntry(HttpExchange exchange, String path) throws IOException {
        String idStr = path.substring("/api/entries/".length());
        int id;
        try {
            id = Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            sendJson(exchange, 400, "{\"error\":\"Invalid id\"}");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> data = Json.parseObject(body);

        String title = data.getOrDefault("title", "").trim();
        String entryText = data.getOrDefault("entry", "").trim();
        String mood = data.getOrDefault("mood", "").trim();
        String date = data.getOrDefault("date", "").trim();
        String image = data.containsKey("image") ? data.get("image").trim() : null;

        if (title.isEmpty() && entryText.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"Title or entry text is required\"}");
            return;
        }

        JournalEntry updated = manager.update(id, date, mood, title, entryText, image);
        if (updated == null) {
            sendJson(exchange, 404, "{\"error\":\"Entry not found\"}");
            return;
        }
        sendJson(exchange, 200, updated.toJson());
    }

    private void handleDeleteEntry(HttpExchange exchange, String path) throws IOException {
        String idStr = path.substring("/api/entries/".length());
        int id;
        try {
            id = Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            sendJson(exchange, 400, "{\"error\":\"Invalid id\"}");
            return;
        }
        boolean removed = manager.delete(id);
        if (removed) {
            sendJson(exchange, 200, "{\"deleted\":" + id + "}");
        } else {
            sendJson(exchange, 404, "{\"error\":\"Entry not found\"}");
        }
    }

    private String entriesToJson(List<JournalEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(entries.get(i).toJson());
        }
        sb.append(']');
        return sb.toString();
    }

    // ------------------------------------------------------------- static ----

    private void serveStatic(HttpExchange exchange, String path) throws IOException {
        if (path.equals("/") || path.isEmpty()) {
            path = "/index.html";
        }
        // prevent path traversal
        String relative = path.replace("\\", "/");
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        Path file = frontendDir.resolve(relative).normalize();
        if (!file.startsWith(frontendDir.normalize()) || !Files.isRegularFile(file)) {
            sendText(exchange, 404, "Not Found");
            return;
        }

        byte[] bytes = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", contentType(file.getFileName().toString()));
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String contentType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    // -------------------------------------------------------------- helpers ---

    private Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return result;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                String key = urlDecode(pair.substring(0, eq));
                String value = urlDecode(pair.substring(eq + 1));
                result.put(key, value);
            } else {
                result.put(urlDecode(pair), "");
            }
        }
        return result;
    }

    private String urlDecode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
