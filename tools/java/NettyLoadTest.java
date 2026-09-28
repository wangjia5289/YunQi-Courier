import yunqi.courier.api.bootstrap.ServiceExportConfig;
import yunqi.courier.api.bootstrap.ServiceReferenceConfig;
import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.kernel.YunqiCourierBootstrap;
import yunqi.courier.kernel.metrics.InvocationMetricsSnapshot;
import yunqi.courier.telemetry.otlp.OtlpInvocationMetricsExporter;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Closed-loop Netty RPC load and restart drill. */
public final class NettyLoadTest {

    public interface EchoService {
        String echo(String value);
    }

    public static final class EchoServiceImpl implements EchoService {
        @Override
        public String echo(String value) {
            return value;
        }
    }

    private NettyLoadTest() {
    }

    public static void main(String[] args) throws Exception {
        int requests = args.length > 0 ? Integer.parseInt(args[0]) : 50_000;
        int concurrency = args.length > 1 ? Integer.parseInt(args[1]) : 32;
        boolean fault = args.length > 2 && Boolean.parseBoolean(args[2]);
        String otlpEndpoint = args.length > 3 ? args[3] : "";
        if (requests <= 0 || concurrency <= 0 || concurrency > 512) {
            throw new IllegalArgumentException("requests must be positive and concurrency must be 1..512");
        }

        int providerPort = availablePort();
        int consumerPort = availablePort();
        CourierConfig providerConfig = config(providerPort);
        CourierConfig consumerConfig = config(consumerPort);
        YunqiCourierBootstrap provider = new YunqiCourierBootstrap(providerConfig);
        YunqiCourierBootstrap consumer = new YunqiCourierBootstrap(consumerConfig);
        OtlpInvocationMetricsExporter exporter = null;
        try {
            provider.export(ServiceExportConfig.of(EchoService.class, new EchoServiceImpl()));
            provider.start();
            consumer.start();
            if (!otlpEndpoint.isBlank()) {
                exporter = OtlpInvocationMetricsExporter.builder()
                        .endpoint(otlpEndpoint)
                        .serviceName(fault ? "yunqi-courier-load-fault" : "yunqi-courier-load")
                        .exportInterval(Duration.ofSeconds(2))
                        .build();
                consumer.registerMetricsExporter(exporter);
            }
            EchoService echo = consumer.refer(ServiceReferenceConfig.of(EchoService.class)
                    .timeoutMillis(5_000));
            for (int i = 0; i < Math.min(1_000, requests); i++) {
                echo.echo("warmup");
            }
            if (fault) {
                runFaultDrill(echo, provider, requests, concurrency);
            } else {
                runLoad(echo, requests, concurrency);
            }
            Thread.sleep(500);
            InvocationMetricsSnapshot metrics = consumer.metrics();
            if (exporter != null) {
                exporter.forceFlush(5, TimeUnit.SECONDS);
            }
            System.out.printf("requests=%d concurrency=%d fault=%s success=%d failure=%d timeout=%d throughput_rps=%.2f p50_ms=%.3f p95_ms=%.3f p99_ms=%.3f%n",
                    requests, concurrency, fault, LAST.success.get(), LAST.failure.get(),
                    metrics.timeoutCount(), LAST.success.get() / LAST.elapsedSeconds,
                    percentile(LAST.latencies, LAST.samples, 0.50),
                    percentile(LAST.latencies, LAST.samples, 0.95),
                    percentile(LAST.latencies, LAST.samples, 0.99));
        } finally {
            if (exporter != null) {
                exporter.close();
            }
            consumer.close();
            provider.close();
        }
    }

    private static final Result LAST = new Result();

    private static void runLoad(EchoService echo, int requests, int concurrency) throws Exception {
        LAST.reset(requests);
        runRequests(echo, requests, concurrency);
    }

    private static void runFaultDrill(EchoService echo, YunqiCourierBootstrap provider,
                                      int requests, int concurrency) throws Exception {
        if (requests < 3) {
            runLoad(echo, requests, concurrency);
            return;
        }
        int normalRequests = Math.max(1, requests / 10);
        int outageRequests = Math.min(2_000, Math.max(1, requests / 10));
        if (normalRequests + outageRequests >= requests) {
            normalRequests = Math.max(1, requests / 3);
            outageRequests = Math.max(1, requests / 3);
        }
        int recoveryRequests = requests - normalRequests - outageRequests;
        LAST.reset(requests);
        runRequests(echo, normalRequests, concurrency);
        provider.stop();
        runRequests(echo, outageRequests, Math.min(concurrency, 16));
        provider.start();
        Thread.sleep(1_500);
        runRequests(echo, recoveryRequests, concurrency);
    }

    private static void runRequests(EchoService echo, int requests, int concurrency) throws Exception {
        AtomicInteger next = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        long started = System.nanoTime();
        for (int i = 0; i < concurrency; i++) {
            executor.execute(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int index; (index = next.getAndIncrement()) < requests; ) {
                        long begin = System.nanoTime();
                        try {
                            if (!"load".equals(echo.echo("load"))) {
                                throw new IllegalStateException("unexpected response");
                            }
                            LAST.success.incrementAndGet();
                        } catch (Throwable failure) {
                            LAST.failure.incrementAndGet();
                        } finally {
                            LAST.addLatency(System.nanoTime() - begin);
                        }
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        if (!ready.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("load workers did not start");
        }
        start.countDown();
        executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.MINUTES)) {
            executor.shutdownNow();
            throw new IllegalStateException("load test did not finish");
        }
        LAST.elapsedSeconds += (System.nanoTime() - started) / 1_000_000_000d;
    }

    private static CourierConfig config(int port) {
        CourierConfig config = new CourierConfig();
        config.setPort(port);
        config.setTimeoutMillis(5_000);
        config.setRegistry("memory");
        config.setClientAutoReconnect(true);
        config.setMaxPendingRequests(50_000);
        return config;
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static double percentile(AtomicReferenceArray<Long> values, int size, double quantile) {
        long[] copy = new long[size];
        for (int i = 0; i < size; i++) {
            copy[i] = values.get(i);
        }
        Arrays.sort(copy);
        int index = Math.min(size - 1, Math.max(0, (int) Math.ceil(quantile * size) - 1));
        return copy[index] / 1_000_000d;
    }

    private static final class Result {
        private AtomicReferenceArray<Long> latencies = new AtomicReferenceArray<>(1);
        private int samples;
        private final AtomicInteger success = new AtomicInteger();
        private final AtomicInteger failure = new AtomicInteger();
        private volatile double elapsedSeconds;

        private void reset(int size) {
            latencies = new AtomicReferenceArray<>(size);
            samples = 0;
            success.set(0);
            failure.set(0);
            elapsedSeconds = 0d;
        }

        private synchronized void addLatency(long nanos) {
            if (samples < latencies.length()) {
                latencies.set(samples++, nanos);
            }
        }
    }
}
