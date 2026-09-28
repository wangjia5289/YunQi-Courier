package yunqi.courier.telemetry.otlp;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporterBuilder;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import yunqi.courier.kernel.metrics.InvocationMetricsSnapshot;
import yunqi.courier.kernel.spi.metrics.InvocationMetricsExporter;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exports YunQi-Courier cumulative invocation snapshots through OTLP/HTTP.
 * The exporter converts cumulative snapshots to deltas so counters are not
 * double-counted when the kernel publishes after every invocation.
 */
public final class OtlpInvocationMetricsExporter implements InvocationMetricsExporter, AutoCloseable {

    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");

    private final SdkMeterProvider meterProvider;
    private final AtomicReference<InvocationMetricsSnapshot> previous = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Attributes attributes;
    private final LongCounter calls;
    private final LongCounter successes;
    private final LongCounter failures;
    private final LongCounter timeouts;
    private final LongCounter retries;
    private final DoubleHistogram latency;

    private OtlpInvocationMetricsExporter(Builder builder) {
        OtlpHttpMetricExporterBuilder exporterBuilder = OtlpHttpMetricExporter.builder()
                .setEndpoint(builder.endpoint)
                .setTimeout(builder.timeout);
        builder.headers.forEach(exporterBuilder::addHeader);
        OtlpHttpMetricExporter metricExporter = exporterBuilder.build();
        PeriodicMetricReader reader = PeriodicMetricReader.builder(metricExporter)
                .setInterval(builder.exportInterval)
                .build();
        Resource resource = Resource.create(Attributes.of(SERVICE_NAME, builder.serviceName));
        meterProvider = SdkMeterProvider.builder()
                .setResource(resource)
                .registerMetricReader(reader)
                .build();
        Meter meter = meterProvider.get("yunqi-courier");
        attributes = Attributes.of(SERVICE_NAME, builder.serviceName);
        calls = meter.counterBuilder("yunqi.rpc.calls").setDescription("RPC calls").build();
        successes = meter.counterBuilder("yunqi.rpc.success").setDescription("Successful RPC calls").build();
        failures = meter.counterBuilder("yunqi.rpc.failure").setDescription("Failed RPC calls").build();
        timeouts = meter.counterBuilder("yunqi.rpc.timeout").setDescription("Timed out RPC calls").build();
        retries = meter.counterBuilder("yunqi.rpc.retries").setDescription("RPC retries").build();
        latency = meter.histogramBuilder("yunqi.rpc.latency").setUnit("ms")
                .setDescription("RPC latency for completed calls").build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public void export(InvocationMetricsSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (closed.get()) {
            return;
        }
        InvocationMetricsSnapshot prior = previous.getAndSet(snapshot);
        if (prior == null) {
            prior = new InvocationMetricsSnapshot(0, 0, 0, 0, 0);
        }
        long successDelta = delta(snapshot.successCount(), prior.successCount());
        long failureDelta = delta(snapshot.failureCount(), prior.failureCount());
        long timeoutDelta = delta(snapshot.timeoutCount(), prior.timeoutCount());
        long retryDelta = delta(snapshot.retryCount(), prior.retryCount());
        long callDelta = successDelta + failureDelta + timeoutDelta;
        add(calls, callDelta);
        add(successes, successDelta);
        add(failures, failureDelta);
        add(timeouts, timeoutDelta);
        add(retries, retryDelta);
        long latencyDelta = delta(snapshot.totalLatencyNanos(), prior.totalLatencyNanos());
        if (callDelta > 0 && latencyDelta >= 0) {
            latency.record((latencyDelta / 1_000_000d) / callDelta, attributes);
        }
    }

    /** Flushes pending points to the OTLP endpoint, bounded by the supplied timeout. */
    public boolean forceFlush(long timeout, TimeUnit unit) {
        if (closed.get()) {
            return false;
        }
        return meterProvider.forceFlush().join(timeout, unit).isSuccess();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            meterProvider.shutdown().join(10, TimeUnit.SECONDS);
        }
    }

    private void add(LongCounter counter, long value) {
        if (value > 0) {
            counter.add(value, attributes);
        }
    }

    private static long delta(long current, long previous) {
        return Math.max(0, current - previous);
    }

    public static final class Builder {
        private String endpoint = "http://127.0.0.1:4318/v1/metrics";
        private String serviceName = "yunqi-courier";
        private Duration exportInterval = Duration.ofSeconds(10);
        private Duration timeout = Duration.ofSeconds(5);
        private Map<String, String> headers = Map.of();

        public Builder endpoint(String endpoint) {
            this.endpoint = requireHttpUri(endpoint, "endpoint");
            return this;
        }

        public Builder serviceName(String serviceName) {
            if (serviceName == null || serviceName.isBlank()) {
                throw new IllegalArgumentException("serviceName must not be blank");
            }
            this.serviceName = serviceName.trim();
            return this;
        }

        public Builder exportInterval(Duration exportInterval) {
            this.exportInterval = requirePositive(exportInterval, "exportInterval");
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = requirePositive(timeout, "timeout");
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            Objects.requireNonNull(headers, "headers");
            this.headers = Map.copyOf(headers);
            return this;
        }

        public OtlpInvocationMetricsExporter build() {
            return new OtlpInvocationMetricsExporter(this);
        }

        private static Duration requirePositive(Duration value, String field) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(field + " must be positive");
            }
            return value;
        }

        private static String requireHttpUri(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            URI uri;
            try {
                uri = URI.create(value.trim());
            } catch (IllegalArgumentException invalidUri) {
                throw new IllegalArgumentException(field + " must be a valid URI", invalidUri);
            }
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException(field + " must be an http(s) URI");
            }
            return value.trim();
        }
    }
}
