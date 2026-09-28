package yunqi.courier.network.netty;

import org.junit.jupiter.api.Test;
import yunqi.courier.api.bootstrap.ServiceExportConfig;
import yunqi.courier.api.bootstrap.ServiceReferenceConfig;
import yunqi.courier.api.exception.RpcException;
import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.common.service.ServiceEndpoint;
import yunqi.courier.common.service.ServiceMetadata;
import yunqi.courier.kernel.YunqiCourierBootstrap;
import yunqi.courier.kernel.metrics.InvocationMetricsSnapshot;
import yunqi.courier.kernel.spi.core.ServiceProviderLoader;
import yunqi.courier.kernel.spi.registry.ServiceRegistry;

import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResilienceNettyRpcE2eTest {

    @Test
    void shouldEnforceReferenceRateLimitBeforeRemoteCall() {
        int port = availablePort();
        AtomicInteger invocationCount = new AtomicInteger();
        CourierConfig providerConfig = config(port);
        YunqiCourierBootstrap provider = new YunqiCourierBootstrap(providerConfig);
        YunqiCourierBootstrap consumer = new YunqiCourierBootstrap(config(availablePort()));
        try {
            provider.export(ServiceExportConfig.of(ResilienceService.class,
                    new ResilienceServiceImpl(invocationCount)));
            provider.start();

            ResilienceService service = consumer.refer(ServiceReferenceConfig.of(ResilienceService.class)
                    .timeoutMillis(1000)
                    .rateLimitPerSecond(1));

            assertEquals("echo:one", service.echo("one"));
            RpcException rateLimited = assertThrows(RpcException.class, () -> service.echo("two"));
            assertTrue(rateLimited.getMessage().contains("Rate limit exceeded"));
            assertEquals(1, invocationCount.get(), "the rejected call must not reach the provider");

            InvocationMetricsSnapshot metrics = consumer.metrics();
            assertEquals(1, metrics.successCount());
            assertEquals(1, metrics.failureCount());
            assertEquals(0, metrics.timeoutCount());
            assertEquals(2, metrics.totalCallCount());
            assertTrue(metrics.totalLatencyNanos() >= 0);
            assertTrue(metrics.averageLatencyMillis() >= 0d);
        } finally {
            consumer.close();
            provider.close();
        }
    }

    @Test
    void shouldOpenCircuitAfterTransportFailures() {
        ServiceRegistry registry = ServiceProviderLoader.load(ServiceRegistry.class, "memory");
        ServiceMetadata metadata = new ServiceMetadata();
        metadata.setServiceName(CircuitService.class.getName());
        metadata.setEndpoint(new ServiceEndpoint("127.0.0.1", unusedPort()));
        registry.register(metadata);

        YunqiCourierBootstrap consumer = new YunqiCourierBootstrap(config(availablePort()));
        try {
            CircuitService service = consumer.refer(ServiceReferenceConfig.of(CircuitService.class)
                    .timeoutMillis(300)
                    .circuitBreaker(true)
                    .circuitBreakerFailureThreshold(2)
                    .circuitBreakerResetTimeoutMillis(10_000));

            assertThrows(RpcException.class, service::ping);
            assertThrows(RpcException.class, service::ping);
            RpcException open = assertThrows(RpcException.class, service::ping);
            assertTrue(open.getCause() != null);
            assertTrue(open.getCause().getMessage().contains("Circuit breaker is open"));
            assertFalse(open.getMessage().contains("Connect failed"),
                    "the third call should be rejected locally once the circuit is open");

            InvocationMetricsSnapshot metrics = consumer.metrics();
            assertEquals(0, metrics.successCount());
            assertEquals(3, metrics.failureCount());
            assertEquals(3, metrics.totalCallCount());
        } finally {
            registry.unregister(metadata);
            consumer.close();
        }
    }

    @Test
    void shouldEnforceRequestDeadlineAndRecordTimeout() {
        int port = availablePort();
        AtomicInteger invocationCount = new AtomicInteger();
        YunqiCourierBootstrap provider = new YunqiCourierBootstrap(config(port));
        YunqiCourierBootstrap consumer = new YunqiCourierBootstrap(config(availablePort()));
        try {
            provider.export(ServiceExportConfig.of(SlowService.class, new SlowServiceImpl(invocationCount)));
            provider.start();

            SlowService service = consumer.refer(ServiceReferenceConfig.of(SlowService.class)
                    .timeoutMillis(80));

            assertThrows(RpcException.class, service::slow);
            awaitInvocation(invocationCount);
            assertEquals(1, invocationCount.get(), "a timeout must not implicitly retry the operation");
            assertEquals(0, consumer.metrics().successCount());
            assertEquals(1, consumer.metrics().timeoutCount());
            assertEquals(1, consumer.metrics().totalCallCount());
        } finally {
            consumer.close();
            provider.close();
        }
    }

    private static CourierConfig config(int port) {
        CourierConfig config = new CourierConfig();
        config.setPort(port);
        config.setConnectTimeoutMillis(100);
        return config;
    }

    private static int availablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new AssertionError("Could not allocate an ephemeral port", e);
        }
    }

    private static int unusedPort() {
        int port = availablePort();
        for (int attempt = 0; attempt < 5; attempt++) {
            try (ServerSocket ignored = new ServerSocket(port)) {
                return port;
            } catch (Exception e) {
                port = availablePort();
            }
        }
        throw new AssertionError("Could not reserve an unused port");
    }

    private static void awaitInvocation(AtomicInteger invocationCount) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (invocationCount.get() == 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for provider invocation", e);
            }
        }
    }

    interface ResilienceService {

        String echo(String value);
    }

    static final class ResilienceServiceImpl implements ResilienceService {

        private final AtomicInteger invocationCount;

        private ResilienceServiceImpl(AtomicInteger invocationCount) {
            this.invocationCount = invocationCount;
        }

        @Override
        public String echo(String value) {
            invocationCount.incrementAndGet();
            return "echo:" + value;
        }
    }

    interface CircuitService {

        String ping();
    }

    interface SlowService {

        String slow();
    }

    static final class SlowServiceImpl implements SlowService {

        private final AtomicInteger invocationCount;

        private SlowServiceImpl(AtomicInteger invocationCount) {
            this.invocationCount = invocationCount;
        }

        @Override
        public String slow() {
            invocationCount.incrementAndGet();
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "slow";
        }
    }
}
