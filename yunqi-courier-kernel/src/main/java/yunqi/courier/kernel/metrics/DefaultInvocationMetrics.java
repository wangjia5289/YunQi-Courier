package yunqi.courier.kernel.metrics;

import yunqi.courier.kernel.spi.metrics.InvocationMetricsExporter;

import java.util.Objects;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

public final class DefaultInvocationMetrics implements InvocationMetrics {

    private static final Executor PUBLISH_EXECUTOR = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4096),
            runnable -> {
                Thread thread = new Thread(runnable, "yunqi-courier-metrics");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    private final LongAdder successCount = new LongAdder();

    private final LongAdder failureCount = new LongAdder();

    private final LongAdder timeoutCount = new LongAdder();

    private final LongAdder retryCount = new LongAdder();

    private final LongAdder totalLatencyNanos = new LongAdder();

    private final CopyOnWriteArrayList<InvocationMetricsExporter> exporters = new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<InvocationMetricsListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void recordSuccess(long latencyNanos) {
        successCount.increment();
        totalLatencyNanos.add(Math.max(0, latencyNanos));
        publish();
    }

    @Override
    public void recordFailure(long latencyNanos) {
        failureCount.increment();
        totalLatencyNanos.add(Math.max(0, latencyNanos));
        publish();
    }

    @Override
    public void recordTimeout(long latencyNanos) {
        timeoutCount.increment();
        totalLatencyNanos.add(Math.max(0, latencyNanos));
        publish();
    }

    @Override
    public void recordRetry() {
        retryCount.increment();
        publish();
    }

    /** Registers an exporter. Duplicate registrations are ignored. */
    public void registerExporter(InvocationMetricsExporter exporter) {
        exporters.addIfAbsent(Objects.requireNonNull(exporter, "exporter"));
    }

    /** Removes a previously registered exporter. */
    public void unregisterExporter(InvocationMetricsExporter exporter) {
        if (exporter != null) {
            exporters.remove(exporter);
        }
    }

    /** Registers a local listener. Duplicate registrations are ignored. */
    public void registerListener(InvocationMetricsListener listener) {
        listeners.addIfAbsent(Objects.requireNonNull(listener, "listener"));
    }

    /** Removes a previously registered listener. */
    public void unregisterListener(InvocationMetricsListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    @Override
    public InvocationMetricsSnapshot snapshot() {
        return new InvocationMetricsSnapshot(
                successCount.sum(),
                failureCount.sum(),
                timeoutCount.sum(),
                retryCount.sum(),
                totalLatencyNanos.sum()
        );
    }

    private void publish() {
        InvocationMetricsSnapshot snapshot = snapshot();
        if (exporters.isEmpty() && listeners.isEmpty()) {
            return;
        }
        var exporterSnapshot = List.copyOf(exporters);
        var listenerSnapshot = List.copyOf(listeners);
        PUBLISH_EXECUTOR.execute(() -> {
            for (InvocationMetricsExporter exporter : exporterSnapshot) {
                try {
                    exporter.export(snapshot);
                } catch (RuntimeException ignored) {
                    // Metrics backends must never break the RPC call being measured.
                }
            }
            for (InvocationMetricsListener listener : listenerSnapshot) {
                try {
                    listener.onSnapshot(snapshot);
                } catch (RuntimeException ignored) {
                    // Local observers are isolated for the same reason as exporters.
                }
            }
        });
    }
}
