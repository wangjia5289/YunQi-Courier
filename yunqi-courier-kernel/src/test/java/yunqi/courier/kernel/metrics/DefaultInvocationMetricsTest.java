package yunqi.courier.kernel.metrics;

import org.junit.jupiter.api.Test;
import yunqi.courier.kernel.spi.metrics.InvocationMetricsExporter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultInvocationMetricsTest {

    @Test
    void shouldPublishCumulativeSnapshotsToExporterAndListener() throws Exception {
        DefaultInvocationMetrics metrics = new DefaultInvocationMetrics();
        List<InvocationMetricsSnapshot> exported = new CopyOnWriteArrayList<>();
        List<InvocationMetricsSnapshot> observed = new CopyOnWriteArrayList<>();
        CountDownLatch published = new CountDownLatch(4);
        InvocationMetricsExporter exporter = snapshot -> {
            exported.add(snapshot);
            published.countDown();
        };
        InvocationMetricsListener listener = snapshot -> {
            observed.add(snapshot);
            published.countDown();
        };

        metrics.registerExporter(exporter);
        metrics.registerExporter(exporter);
        metrics.registerListener(listener);
        metrics.registerListener(listener);

        metrics.recordSuccess(10);
        metrics.recordRetry();

        assertTrue(published.await(2, TimeUnit.SECONDS));
        assertEquals(2, exported.size());
        assertEquals(2, observed.size());
        assertEquals(1, exported.get(0).successCount());
        assertEquals(0, exported.get(0).retryCount());
        assertEquals(1, exported.get(1).successCount());
        assertEquals(1, exported.get(1).retryCount());
        assertEquals(metrics.snapshot(), exported.get(1));
    }

    @Test
    void shouldIsolateObserverFailuresAndSupportUnregister() throws Exception {
        DefaultInvocationMetrics metrics = new DefaultInvocationMetrics();
        List<InvocationMetricsSnapshot> exported = new CopyOnWriteArrayList<>();
        CountDownLatch published = new CountDownLatch(1);
        InvocationMetricsExporter failingExporter = snapshot -> {
            throw new IllegalStateException("exporter unavailable");
        };
        InvocationMetricsListener failingListener = snapshot -> {
            throw new IllegalStateException("listener unavailable");
        };
        InvocationMetricsExporter healthyExporter = snapshot -> {
            exported.add(snapshot);
            published.countDown();
        };

        metrics.registerExporter(failingExporter);
        metrics.registerListener(failingListener);
        metrics.registerExporter(healthyExporter);
        metrics.recordFailure(5);
        assertTrue(published.await(2, TimeUnit.SECONDS));
        assertEquals(1, exported.size());
        assertEquals(1, metrics.snapshot().failureCount());

        metrics.unregisterExporter(healthyExporter);
        metrics.unregisterListener(failingListener);
        metrics.recordTimeout(7);
        assertEquals(1, exported.size());
        assertEquals(1, metrics.snapshot().timeoutCount());
    }

    @Test
    void shouldRejectNullObserversAndExposeNoopExporter() {
        DefaultInvocationMetrics metrics = new DefaultInvocationMetrics();

        assertThrows(NullPointerException.class, () -> metrics.registerExporter(null));
        assertThrows(NullPointerException.class, () -> metrics.registerListener(null));
        assertNotNull(InvocationMetricsExporter.NOOP);
        assertEquals(InvocationMetricsExporter.NOOP, InvocationMetricsExporter.noop());

        metrics.registerExporter(InvocationMetricsExporter.NOOP);
        metrics.recordSuccess(1);
        assertEquals(1, metrics.snapshot().successCount());
    }
}
