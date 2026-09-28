package yunqi.courier.kernel.metrics;

/** Receives cumulative metrics snapshots after a metrics event is recorded. */
@FunctionalInterface
public interface InvocationMetricsListener {

    void onSnapshot(InvocationMetricsSnapshot snapshot);
}
