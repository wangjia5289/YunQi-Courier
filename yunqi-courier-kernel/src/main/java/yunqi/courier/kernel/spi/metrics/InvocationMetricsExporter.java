package yunqi.courier.kernel.spi.metrics;

import yunqi.courier.kernel.metrics.InvocationMetricsSnapshot;

/**
 * Extension point for exporting the cumulative invocation metrics snapshot.
 *
 * <p>Exporters run on a bounded daemon worker so a slow backend cannot extend
 * an RPC deadline. Implementations should still be bounded and non-blocking;
 * under sustained overload metrics events may be dropped.</p>
 */
@FunctionalInterface
public interface InvocationMetricsExporter {

    /** A default exporter used when no external metrics backend is configured. */
    InvocationMetricsExporter NOOP = snapshot -> {
    };

    void export(InvocationMetricsSnapshot snapshot);

    /** Returns the default no-op exporter. */
    static InvocationMetricsExporter noop() {
        return NOOP;
    }
}
