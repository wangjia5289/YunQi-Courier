package yunqi.courier.kernel.invoker;

import yunqi.courier.api.bootstrap.ServiceReferenceConfig;
import yunqi.courier.api.annotation.CourierIdempotent;
import yunqi.courier.api.context.RequestContext;
import yunqi.courier.api.exception.RpcException;
import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.common.protocol.CourierRequest;
import yunqi.courier.common.protocol.CourierResponse;
import yunqi.courier.common.protocol.CourierRequestValidator;
import yunqi.courier.common.protocol.ResponseCode;
import yunqi.courier.common.service.ServiceMetadata;
import yunqi.courier.common.util.RequestIdGenerator;
import yunqi.courier.common.exception.RemotingException;
import yunqi.courier.kernel.spi.network.NetworkClient;
import yunqi.courier.kernel.spi.registry.ServiceRegistry;
import yunqi.courier.kernel.spi.traffic.loadbalancing.TrafficLoadBalancer;
import yunqi.courier.kernel.metrics.DefaultInvocationMetrics;
import yunqi.courier.kernel.metrics.InvocationMetrics;
import yunqi.courier.kernel.resilience.CircuitBreaker;
import yunqi.courier.kernel.resilience.TokenBucketRateLimiter;
import yunqi.courier.kernel.spi.traffic.loadbalancing.ActiveRequestTracker;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class ServiceInvocationHandler<T> implements InvocationHandler, AutoCloseable {

    private final ServiceReferenceConfig<T> referenceConfig;

    private final CourierConfig courierConfig;

    private final ServiceRegistry serviceRegistry;

    private final TrafficLoadBalancer trafficLoadBalancer;

    private final NetworkClient networkClient;

    private final InvocationMetrics metrics;

    private final TokenBucketRateLimiter rateLimiter;

    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    private volatile List<ServiceMetadata> watchedProviders = List.of();

    private volatile AutoCloseable watchHandle;

    public ServiceInvocationHandler(ServiceReferenceConfig<T> referenceConfig,
                                    CourierConfig courierConfig,
                                    ServiceRegistry serviceRegistry,
                                    TrafficLoadBalancer trafficLoadBalancer,
                                    NetworkClient networkClient) {
        this(referenceConfig, courierConfig, serviceRegistry, trafficLoadBalancer, networkClient,
                new DefaultInvocationMetrics());
    }

    public ServiceInvocationHandler(ServiceReferenceConfig<T> referenceConfig,
                                    CourierConfig courierConfig,
                                    ServiceRegistry serviceRegistry,
                                    TrafficLoadBalancer trafficLoadBalancer,
                                    NetworkClient networkClient,
                                    InvocationMetrics metrics) {
        this.referenceConfig = referenceConfig;
        this.courierConfig = courierConfig;
        this.serviceRegistry = serviceRegistry;
        this.trafficLoadBalancer = trafficLoadBalancer;
        this.networkClient = networkClient;
        this.metrics = metrics;
        this.rateLimiter = new TokenBucketRateLimiter(referenceConfig.getRateLimitPerSecond());
        try {
            this.watchHandle = serviceRegistry.watch(serviceKey(), (ignored, providers) ->
                    watchedProviders = validProviders(providers));
        } catch (RuntimeException ignored) {
            // Discovery remains available even when a registry does not support watches.
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy != null && proxy == (args == null ? null : args[0]);
                case "hashCode" -> proxy == null ? 0 : System.identityHashCode(proxy);
                case "toString" -> "YunqiCourierProxy{" + serviceKey() + "}";
                default -> method.invoke(this, args);
            };
        }
        long invocationStartNanos = System.nanoTime();
        if (!rateLimiter.tryAcquire()) {
            metrics.recordFailure(System.nanoTime() - invocationStartNanos);
            throw new RpcException(ResponseCode.OVERLOADED,
                    "Rate limit exceeded, service=" + serviceKey());
        }
        int timeoutMillis = referenceConfig.getTimeoutMillis() > 0 ? referenceConfig.getTimeoutMillis() : courierConfig.getTimeoutMillis();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        List<ServiceMetadata> providers;
        try {
            List<ServiceMetadata> watched = watchedProviders;
            providers = watched.isEmpty() ? validProviders(serviceRegistry.discover(serviceKey())) : watched;
            if (courierConfig.isActiveHealthChecks() && !providers.isEmpty()) {
                providers = probeProviders(providers, deadline);
            }
        } catch (RpcException e) {
            metrics.recordFailure(System.nanoTime() - invocationStartNanos);
            throw e;
        } catch (RuntimeException e) {
            metrics.recordFailure(System.nanoTime() - invocationStartNanos);
            throw new RpcException(ResponseCode.UNAVAILABLE,
                    "Service discovery failed, service=" + serviceKey(), e);
        }
        if (deadlineExpired(deadline)) {
            metrics.recordTimeout(System.nanoTime() - invocationStartNanos);
            throw new RpcException(ResponseCode.TIMEOUT,
                    "Remote invoke timed out during service discovery, service=" + serviceKey()
                            + ", timeoutMillis=" + timeoutMillis);
        }
        if (providers.isEmpty()) {
            metrics.recordFailure(System.nanoTime() - invocationStartNanos);
            throw new RpcException(ResponseCode.UNAVAILABLE, "No available provider, service=" + serviceKey());
        }
        int maxAttempts = referenceConfig.isRetryable()
                ? 1 + (referenceConfig.getRetries() >= 0 ? referenceConfig.getRetries() : courierConfig.getRetries())
                : 1;
        if (maxAttempts > 1 && !referenceConfig.isIdempotent()
                && !method.isAnnotationPresent(CourierIdempotent.class)) {
            metrics.recordFailure(System.nanoTime() - invocationStartNanos);
            throw new RpcException(ResponseCode.BAD_REQUEST,
                    "Retry requires an idempotent operation, service=" + serviceKey()
                            + ", method=" + method.getName());
        }
        Set<String> failedEndpoints = new HashSet<>();
        Throwable lastFailure = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            List<ServiceMetadata> candidates = availableProviders(providers, failedEndpoints);
            if (candidates.isEmpty()) {
                failedEndpoints.clear();
                candidates = providers;
            }
            if (deadlineExpired(deadline)) {
                metrics.recordTimeout(System.nanoTime() - invocationStartNanos);
                throw new RpcException(ResponseCode.TIMEOUT,
                        "Remote invoke timed out, service=" + serviceKey()
                                + ", timeoutMillis=" + timeoutMillis, lastFailure);
            }
            ServiceMetadata provider;
            try {
                provider = trafficLoadBalancer.select(candidates);
            } catch (RuntimeException e) {
                metrics.recordFailure(System.nanoTime() - invocationStartNanos);
                throw new RpcException(ResponseCode.UNAVAILABLE,
                        "Load balancer failed, service=" + serviceKey(), e);
            }
            if (provider == null || provider.getEndpoint() == null || !provider.getEndpoint().isValid()) {
                metrics.recordFailure(System.nanoTime() - invocationStartNanos);
                throw new RpcException(ResponseCode.UNAVAILABLE,
                        "Load balancer returned an invalid provider, service=" + serviceKey());
            }
            CircuitBreaker circuitBreaker = circuitBreaker(provider);
            if (circuitBreaker != null && !circuitBreaker.allowRequest()) {
                lastFailure = new RpcException("Circuit breaker is open, endpoint=" + provider.getEndpoint());
                failedEndpoints.add(provider.getEndpoint().address());
                if (attempt + 1 < maxAttempts) {
                    metrics.recordRetry();
                    backoff(deadline, attempt);
                    continue;
                }
                break;
            }
            int remainingMillis = remainingMillis(deadline);
            if (remainingMillis <= 0) {
                metrics.recordTimeout(System.nanoTime() - invocationStartNanos);
                throw new RpcException(ResponseCode.TIMEOUT,
                        "Remote invoke timed out, service=" + serviceKey()
                                + ", timeoutMillis=" + timeoutMillis, lastFailure);
            }
            CompletableFuture<CourierResponse> responseFuture = null;
            boolean trackedRequest = false;
            try {
                CourierRequest request = buildRequest(method, args);
                CourierRequestValidator.validate(request);
                if (trafficLoadBalancer instanceof ActiveRequestTracker tracker) {
                    tracker.onRequestStart(provider);
                    trackedRequest = true;
                }
                responseFuture = networkClient.sendRequest(request, provider.getEndpoint(), remainingMillis);
                CourierResponse response = responseFuture.get(remainingMillis, TimeUnit.MILLISECONDS);
                if (!response.success()) {
                    String message = "Remote invoke failed, code=" + response.getCode()
                            + ", errorClass=" + response.getErrorClass() + ", message=" + response.getMessage();
                    if (!response.retryableTransportFailure()) {
                        throw new RpcException(response.responseCode(), message, null, response.getErrorClass());
                    }
                    lastFailure = new RpcException(response.responseCode(), message, null, response.getErrorClass());
                    if (circuitBreaker != null) {
                        circuitBreaker.recordFailure();
                    }
                    failedEndpoints.add(provider.getEndpoint().address());
                    if (attempt + 1 < maxAttempts) {
                        metrics.recordRetry();
                        backoff(deadline, attempt);
                        continue;
                    }
                    break;
                }
                if (circuitBreaker != null) {
                    circuitBreaker.recordSuccess();
                }
                metrics.recordSuccess(System.nanoTime() - invocationStartNanos);
                return response.getData();
            } catch (ExecutionException e) {
                lastFailure = unwrap(e);
                if (!isRetryableTransportFailure(lastFailure)) {
                    metrics.recordFailure(System.nanoTime() - invocationStartNanos);
                    throw new RpcException("Remote transport failed, service=" + serviceKey(), lastFailure);
                }
                if (circuitBreaker != null) {
                    circuitBreaker.recordFailure();
                }
                failedEndpoints.add(provider.getEndpoint().address());
            } catch (java.util.concurrent.TimeoutException e) {
                lastFailure = e;
                if (circuitBreaker != null) {
                    circuitBreaker.recordFailure();
                }
                if (responseFuture != null) {
                    responseFuture.cancel(true);
                }
                failedEndpoints.add(provider.getEndpoint().address());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (responseFuture != null) {
                    responseFuture.cancel(true);
                }
                metrics.recordFailure(System.nanoTime() - invocationStartNanos);
                throw new RpcException("Remote invoke interrupted, service=" + serviceKey(), e);
            } catch (RpcException e) {
                if (e.getMessage() != null && e.getMessage().startsWith("Remote invoke failed")) {
                    metrics.recordFailure(System.nanoTime() - invocationStartNanos);
                }
                throw e;
            } catch (RuntimeException e) {
                lastFailure = e;
                if (!isRetryableTransportFailure(e)) {
                    metrics.recordFailure(System.nanoTime() - invocationStartNanos);
                    throw new RpcException("Remote transport failed, service=" + serviceKey(), e);
                }
                if (circuitBreaker != null) {
                    circuitBreaker.recordFailure();
                }
                failedEndpoints.add(provider.getEndpoint().address());
            } finally {
                if (trackedRequest && trafficLoadBalancer instanceof ActiveRequestTracker tracker) {
                    tracker.onRequestEnd(provider);
                }
            }
            if (attempt + 1 < maxAttempts) {
                metrics.recordRetry();
                backoff(deadline, attempt);
            }
        }
        if (isTimeoutFailure(lastFailure)) {
            metrics.recordTimeout(System.nanoTime() - invocationStartNanos);
        } else {
            metrics.recordFailure(System.nanoTime() - invocationStartNanos);
        }
        ResponseCode failureCode = lastFailure instanceof RpcException rpcException
                ? rpcException.getResponseCode() : null;
        if (failureCode == null && isTimeoutFailure(lastFailure)) {
            failureCode = ResponseCode.TIMEOUT;
        }
        String remoteErrorClass = lastFailure instanceof RpcException rpcException
                ? rpcException.getRemoteErrorClass() : null;
        throw new RpcException(failureCode,
                "Remote invoke failed after " + maxAttempts + " attempt(s), service=" + serviceKey(),
                lastFailure, remoteErrorClass);
    }

    private boolean isTimeoutFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            if (current instanceof RemotingException) {
                String message = current.getMessage();
                if (message != null && (message.contains("timeout")
                        || message.contains("Timeout")
                        || message.contains("deadline exceeded")
                        || message.contains("Deadline exceeded"))) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isRetryableTransportFailure(Throwable failure) {
        if (failure == null) {
            return false;
        }
        Throwable current = failure;
        while (current != null) {
            if (current instanceof java.util.concurrent.TimeoutException
                    || current instanceof RemotingException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private CircuitBreaker circuitBreaker(ServiceMetadata provider) {
        if (!referenceConfig.isCircuitBreakerEnabled() || provider == null || provider.getEndpoint() == null) {
            return null;
        }
        return circuitBreakers.computeIfAbsent(provider.getEndpoint().address(), ignored ->
                new CircuitBreaker(referenceConfig.getCircuitBreakerFailureThreshold(),
                        referenceConfig.getCircuitBreakerResetTimeoutMillis()));
    }

    private List<ServiceMetadata> availableProviders(List<ServiceMetadata> providers, Set<String> failedEndpoints) {
        List<ServiceMetadata> available = new ArrayList<>();
        for (ServiceMetadata provider : providers) {
            if (provider != null && provider.getEndpoint() != null
                    && !failedEndpoints.contains(provider.getEndpoint().address())) {
                available.add(provider);
            }
        }
        return available;
    }

    private List<ServiceMetadata> validProviders(List<ServiceMetadata> providers) {
        if (providers == null || providers.isEmpty()) {
            return List.of();
        }
        return providers.stream()
                .filter(provider -> provider != null && provider.getEndpoint() != null
                        && provider.getEndpoint().isValid())
                .toList();
    }

    private List<ServiceMetadata> probeProviders(List<ServiceMetadata> providers, long deadline) {
        List<ServiceMetadata> healthy = new ArrayList<>();
        for (ServiceMetadata provider : providers) {
            int remainingMillis = remainingMillis(deadline);
            if (remainingMillis <= 0) {
                break;
            }
            int probeTimeout = Math.min(remainingMillis, courierConfig.getHealthCheckIntervalMillis());
            boolean available = false;
            try {
                available = Boolean.TRUE.equals(networkClient.checkEndpoint(provider.getEndpoint(), probeTimeout)
                        .get(probeTimeout, TimeUnit.MILLISECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RpcException("Remote invoke interrupted during health probe, service=" + serviceKey(),
                        interrupted);
            } catch (Exception ignored) {
                // Treat probe failures as unhealthy and allow another provider to serve the call.
            }
            try {
                serviceRegistry.updateHealth(provider, available);
            } catch (RuntimeException ignored) {
                // Health reporting is advisory and cannot fail the invocation.
            }
            if (available) {
                healthy.add(provider);
            }
        }
        return healthy;
    }

    private boolean deadlineExpired(long deadline) {
        return deadline - System.nanoTime() <= 0;
    }

    private int remainingMillis(long deadline) {
        long remainingNanos = deadline - System.nanoTime();
        return remainingNanos <= 0 ? 0 : (int) Math.max(1,
                Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
    }

    private void backoff(long deadline, int attempt) {
        long delayMillis = (long) courierConfig.getRetryBackoffMillis() * (attempt + 1);
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0 || delayMillis <= 0) {
            return;
        }
        LockSupport.parkNanos(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(delayMillis)));
        if (Thread.interrupted()) {
            throw new RpcException("Remote invoke interrupted during retry backoff, service=" + serviceKey());
        }
    }

    private Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException completionException && completionException.getCause() != null) {
            return completionException.getCause();
        }
        return throwable.getCause() == null ? throwable : throwable.getCause();
    }

    private CourierRequest buildRequest(Method method, Object[] args) {
        CourierRequest request = new CourierRequest();
        request.setRequestId(RequestIdGenerator.nextId());
        request.setAuthenticationToken(courierConfig.getAuthToken());
        request.setTraceId(RequestContext.getTraceId());
        request.setServiceName(serviceKey());
        request.setMethodName(method.getName());
        request.setParameterTypeNames(Arrays.stream(method.getParameterTypes()).map(Class::getName).toArray(String[]::new));
        request.setParameters(args == null ? new Object[0] : args);
        request.setAttachments(RequestContext.getAttachments());
        return request;
    }

    private String serviceKey() {
        return referenceConfig.getInterfaceClass().getName() + ":" + referenceConfig.getGroup() + ":" + referenceConfig.getVersion();
    }

    @Override
    public void close() {
        AutoCloseable handle = watchHandle;
        watchHandle = null;
        if (handle != null) {
            try {
                handle.close();
            } catch (Exception ignored) {
                // Registry watch cleanup is best effort during bootstrap shutdown.
            }
        }
    }
}
