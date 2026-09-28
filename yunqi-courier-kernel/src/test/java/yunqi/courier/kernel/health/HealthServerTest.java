package yunqi.courier.kernel.health;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HealthServerTest {

    @Test
    void servesLiveAndReadinessStates() throws Exception {
        AtomicBoolean ready = new AtomicBoolean();
        try (HealthServer server = HealthServer.start("127.0.0.1", 0, ready::get)) {
            HttpClient client = HttpClient.newHttpClient();
            URI live = URI.create("http://127.0.0.1:" + server.port() + "/health/live");
            URI readiness = URI.create("http://127.0.0.1:" + server.port() + "/health/ready");
            assertEquals(200, client.send(HttpRequest.newBuilder(live).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(503, client.send(HttpRequest.newBuilder(readiness).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            ready.set(true);
            assertEquals(200, client.send(HttpRequest.newBuilder(readiness).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
}
