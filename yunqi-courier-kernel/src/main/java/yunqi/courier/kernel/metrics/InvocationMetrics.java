package yunqi.courier.kernel.metrics;

public interface InvocationMetrics {

    void recordSuccess(long latencyNanos);

    void recordFailure(long latencyNanos);

    void recordTimeout(long latencyNanos);

    void recordRetry();

    InvocationMetricsSnapshot snapshot();
}
