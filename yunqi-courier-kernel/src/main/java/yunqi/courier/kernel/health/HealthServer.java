package yunqi.courier.kernel.health;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** Small opt-in management server for container health probes. */
public final class HealthServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;

    private HealthServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    public static HealthServer start(String host, int port, BooleanSupplier readiness) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(readiness, "readiness");
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 16);
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            server.createContext("/health/live", exchange -> respond(exchange, 200, "{\"status\":\"UP\"}"));
            server.createContext("/health/ready", exchange -> {
                boolean ready = readiness.getAsBoolean();
                respond(exchange, ready ? 200 : 503,
                        ready ? "{\"status\":\"UP\"}" : "{\"status\":\"DOWN\"}");
            });
            server.createContext("/health", exchange -> respond(exchange, 404, "{\"status\":\"NOT_FOUND\"}"));
            server.start();
            return new HealthServer(server, executor);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start management health server", e);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
