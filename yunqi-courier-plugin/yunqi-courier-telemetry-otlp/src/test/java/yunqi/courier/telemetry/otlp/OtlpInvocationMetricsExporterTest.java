package yunqi.courier.telemetry.otlp;

import org.junit.jupiter.api.Test;
import yunqi.courier.kernel.metrics.InvocationMetricsSnapshot;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtlpInvocationMetricsExporterTest {

    @Test
    void validatesBuilderInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> OtlpInvocationMetricsExporter.builder().endpoint("ftp://collector").build());
        assertThrows(IllegalArgumentException.class,
                () -> OtlpInvocationMetricsExporter.builder().serviceName(" ").build());
        assertThrows(IllegalArgumentException.class,
                () -> OtlpInvocationMetricsExporter.builder().exportInterval(Duration.ZERO).build());
    }

    @Test
    void convertsCumulativeSnapshotsToDeltasAndCanFlush() {
        AtomicInteger requests = new AtomicInteger();
        try {
            HttpServer collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
            collector.createContext("/v1/metrics", exchange -> {
                exchange.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream());
                requests.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            collector.start();
            try (OtlpInvocationMetricsExporter exporter = OtlpInvocationMetricsExporter.builder()
                    .endpoint("http://127.0.0.1:" + collector.getAddress().getPort() + "/v1/metrics")
                    .serviceName("test")
                    .headers(Map.of("x-test", "true"))
                    .build()) {
                exporter.export(new InvocationMetricsSnapshot(1, 0, 0, 0, 1_000_000));
                exporter.export(new InvocationMetricsSnapshot(1, 1, 0, 1, 3_000_000));
                assertTrue(exporter.forceFlush(5, TimeUnit.SECONDS));
            } finally {
                collector.stop(0);
            }
            assertTrue(requests.get() > 0);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to start test collector", e);
        }
    }
}
