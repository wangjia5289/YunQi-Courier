package yunqi.courier.kernel.invoker;

import org.junit.jupiter.api.Test;
import yunqi.courier.api.annotation.CourierIdempotent;
import yunqi.courier.api.bootstrap.ServiceReferenceConfig;
import yunqi.courier.api.exception.RpcException;
import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.common.protocol.CourierResponse;
import yunqi.courier.common.protocol.ResponseCode;
import yunqi.courier.common.service.ServiceEndpoint;
import yunqi.courier.common.service.ServiceMetadata;
import yunqi.courier.kernel.spi.network.NetworkClient;
import yunqi.courier.kernel.spi.registry.ServiceRegistry;
import yunqi.courier.kernel.spi.traffic.loadbalancing.TrafficLoadBalancer;
import yunqi.courier.kernel.metrics.DefaultInvocationMetrics;
import yunqi.courier.kernel.metrics.InvocationMetrics;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceInvocationHandlerTest {

    @Test
    void retryMustBeRejectedWhenOperationIsNotIdempotent() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        ServiceInvocationHandler<UnsafeService> handler = handler(
                ServiceReferenceConfig.of(UnsafeService.class).retryable(true).retries(2), sends);

        Method method = UnsafeService.class.getMethod("write", String.class);

        assertThrows(RpcException.class, () -> handler.invoke(null, method, new Object[]{"value"}));
        assertEquals(0, sends.get());
    }

    @Test
    void methodAnnotationAllowsRetryableReference() throws Throwable {
        AtomicInteger sends = new AtomicInteger();
        ServiceInvocationHandler<SafeService> handler = handler(
                ServiceReferenceConfig.of(SafeService.class).retryable(true).retries(2), sends);

        Method method = SafeService.class.getMethod("read", String.class);

        assertEquals("value", handler.invoke(null, method, new Object[]{"value"}));
        assertEquals(1, sends.get());
    }

    @Test
    void shouldCountBusinessFailureOnce() throws Throwable {
        AtomicInteger sends = new AtomicInteger();
        DefaultInvocationMetrics metrics = new DefaultInvocationMetrics();
        ServiceInvocationHandler<SafeService> handler = handler(
                ServiceReferenceConfig.of(SafeService.class), sends, metrics, true);

        Method method = SafeService.class.getMethod("read", String.class);

        assertThrows(RpcException.class, () -> handler.invoke(null, method, new Object[]{"value"}));
        assertEquals(1, metrics.snapshot().failureCount());
    }

    @Test
    void shouldExposeStructuredRemoteErrorCode() throws Throwable {
        AtomicInteger sends = new AtomicInteger();
        ServiceInvocationHandler<SafeService> handler = handler(
                ServiceReferenceConfig.of(SafeService.class), sends, new DefaultInvocationMetrics(),
                ResponseCode.FORBIDDEN);

        Method method = SafeService.class.getMethod("read", String.class);

        RpcException failure = assertThrows(RpcException.class,
                () -> handler.invoke(null, method, new Object[]{"value"}));
        assertEquals(ResponseCode.FORBIDDEN, failure.getResponseCode());
        assertEquals("java.lang.IllegalStateException", failure.getRemoteErrorClass());
    }

    @Test
    void shouldKeepProxyObjectMethodIdentityContract() throws Throwable {
        ServiceInvocationHandler<SafeService> handler = handler(
                ServiceReferenceConfig.of(SafeService.class), new AtomicInteger());
        Object proxy = new Object();

        assertTrue((Boolean) handler.invoke(proxy, Object.class.getMethod("equals", Object.class),
                new Object[]{proxy}));
        assertEquals(System.identityHashCode(proxy), handler.invoke(proxy, Object.class.getMethod("hashCode"), null));
        assertTrue(((String) handler.invoke(proxy, Object.class.getMethod("toString"), null))
                .startsWith("YunqiCourierProxy{"));
    }

    private <T> ServiceInvocationHandler<T> handler(ServiceReferenceConfig<T> referenceConfig,
                                                    AtomicInteger sends) {
        return handler(referenceConfig, sends, new DefaultInvocationMetrics(), false);
    }

    private <T> ServiceInvocationHandler<T> handler(ServiceReferenceConfig<T> referenceConfig,
                                                    AtomicInteger sends,
                                                    InvocationMetrics metrics,
                                                    boolean businessFailure) {
        return handler(referenceConfig, sends, metrics,
                businessFailure ? ResponseCode.FAILURE : null);
    }

    private <T> ServiceInvocationHandler<T> handler(ServiceReferenceConfig<T> referenceConfig,
                                                    AtomicInteger sends,
                                                    InvocationMetrics metrics,
                                                    ResponseCode failureCode) {
        CourierConfig config = new CourierConfig();
        ServiceMetadata metadata = new ServiceMetadata();
        metadata.setServiceName(referenceConfig.getInterfaceClass().getName());
        metadata.setGroup(referenceConfig.getGroup());
        metadata.setVersion(referenceConfig.getVersion());
        metadata.setEndpoint(new ServiceEndpoint("127.0.0.1", 21880));

        ServiceRegistry registry = new ServiceRegistry() {
            @Override
            public void register(ServiceMetadata serviceMetadata) {
            }

            @Override
            public void unregister(ServiceMetadata serviceMetadata) {
            }

            @Override
            public List<ServiceMetadata> discover(String serviceKey) {
                return List.of(metadata);
            }
        };
        TrafficLoadBalancer loadBalancer = providers -> providers.get(0);
        NetworkClient client = new NetworkClient() {
            @Override
            public CompletableFuture<CourierResponse> sendRequest(
                yunqi.courier.common.protocol.CourierRequest request,
                    ServiceEndpoint endpoint,
                    int timeoutMillis) {
                sends.incrementAndGet();
                CourierResponse response = failureCode == null
                        ? CourierResponse.success(request.getRequestId(), request.getParameters()[0])
                        : CourierResponse.failure(request.getRequestId(), failureCode,
                        new IllegalStateException("business"));
                return CompletableFuture.completedFuture(response);
            }

            @Override
            public void close() {
            }
        };
        return new ServiceInvocationHandler<>(referenceConfig, config, registry, loadBalancer, client, metrics);
    }

    interface UnsafeService {
        String write(String value);
    }

    interface SafeService {
        @CourierIdempotent
        String read(String value);
    }
}
