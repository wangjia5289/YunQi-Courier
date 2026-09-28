package yunqi.courier.kernel.metrics;

public record InvocationMetricsSnapshot(
        long successCount,
        long failureCount,
        long timeoutCount,
        long retryCount,
        long totalLatencyNanos
) {

    public long totalCallCount() {
        return successCount + failureCount + timeoutCount;
    }

    public double averageLatencyMillis() {
        long calls = totalCallCount();
        return calls == 0 ? 0d : totalLatencyNanos / 1_000_000d / calls;
    }
}
