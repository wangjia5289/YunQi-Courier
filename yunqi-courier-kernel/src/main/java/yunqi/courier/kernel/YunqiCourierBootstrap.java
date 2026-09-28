package yunqi.courier.kernel;

import yunqi.courier.api.bootstrap.ServiceExportConfig;
import yunqi.courier.api.bootstrap.ServiceReferenceConfig;
import yunqi.courier.api.bootstrap.AsyncServiceReference;
import yunqi.courier.api.annotation.CourierProvider;
import yunqi.courier.api.annotation.CourierReference;
import yunqi.courier.api.context.RequestContext;
import yunqi.courier.api.exception.RpcException;
import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.common.service.ServiceEndpoint;
import yunqi.courier.common.service.ServiceMetadata;
import yunqi.courier.kernel.core.DefaultRequestProcessor;
import yunqi.courier.kernel.invoker.ServiceInvocationHandler;
import yunqi.courier.kernel.registry.ServiceRepository;
import yunqi.courier.kernel.spi.core.ServiceProviderLoader;
import yunqi.courier.kernel.spi.network.NetworkClient;
import yunqi.courier.kernel.spi.network.NetworkServer;
import yunqi.courier.kernel.spi.proxying.ProxyFactory;
import yunqi.courier.kernel.spi.registry.ServiceRegistry;
import yunqi.courier.kernel.spi.traffic.loadbalancing.TrafficLoadBalancer;
import yunqi.courier.kernel.spi.security.RequestAccessController;
import yunqi.courier.kernel.security.DefaultRequestAccessController;
import yunqi.courier.kernel.metrics.DefaultInvocationMetrics;
import yunqi.courier.kernel.metrics.InvocationMetricsSnapshot;
import yunqi.courier.kernel.metrics.InvocationMetricsListener;
import yunqi.courier.kernel.spi.metrics.InvocationMetricsExporter;
import yunqi.courier.kernel.health.HealthServer;

import java.util.ArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;

public class YunqiCourierBootstrap {

    private static final System.Logger LOGGER = System.getLogger(YunqiCourierBootstrap.class.getName());

    private final CourierConfig courierConfig;

    private final ServiceRepository serviceRepository = new ServiceRepository();

    private final List<ServiceMetadata> exportedServices = new CopyOnWriteArrayList<>();

    private final Map<String, Object> exportedImplementations = new ConcurrentHashMap<>();

    private final List<ServiceInvocationHandler<?>> references = new CopyOnWriteArrayList<>();

    private ServiceRegistry serviceRegistry;

    private TrafficLoadBalancer trafficLoadBalancer;

    private NetworkClient networkClient;

    private NetworkServer networkServer;

    private RequestAccessController requestAccessController;

    private RequestAccessController configuredAccessController;

    private volatile boolean started;

    private final DefaultInvocationMetrics invocationMetrics = new DefaultInvocationMetrics();

    private boolean componentsClosed;

    private ScheduledExecutorService leaseRenewer;

    private ExecutorService asyncExecutor;

    private ScheduledFuture<?> leaseRenewalTask;

    private HealthServer healthServer;

    private final Set<AsyncCall> asyncCalls = ConcurrentHashMap.newKeySet();

    public YunqiCourierBootstrap() {
        this(new CourierConfig());
    }

    public YunqiCourierBootstrap(CourierConfig courierConfig) {
        this.courierConfig = Objects.requireNonNull(courierConfig, "courierConfig").copy();
        this.courierConfig.validate();
        initComponents();
    }

    public static YunqiCourierBootstrap create() {
        return new YunqiCourierBootstrap();
    }

    /** Supplies a custom authentication/authorization policy before start. */
    public synchronized YunqiCourierBootstrap accessController(RequestAccessController controller) {
        if (started) {
            throw new IllegalStateException("accessController must be configured before start");
        }
        this.configuredAccessController = Objects.requireNonNull(controller, "controller");
        this.requestAccessController = controller;
        return this;
    }

    public synchronized <T> YunqiCourierBootstrap export(ServiceExportConfig<T> exportConfig) {
        Objects.requireNonNull(exportConfig, "exportConfig");
        ServiceMetadata serviceMetadata = new ServiceMetadata();
        serviceMetadata.setServiceName(exportConfig.getInterfaceClass().getName());
        serviceMetadata.setGroup(exportConfig.getGroup());
        serviceMetadata.setVersion(exportConfig.getVersion());
        String advertisedHost = courierConfig.getAdvertisedHost() == null
                ? courierConfig.getHost() : courierConfig.getAdvertisedHost();
        int advertisedPort = courierConfig.getAdvertisedPort() == -1
                ? courierConfig.getPort() : courierConfig.getAdvertisedPort();
        if (isWildcardHost(advertisedHost)) {
            throw new IllegalArgumentException("A non-wildcard advertisedHost is required when exporting a service");
        }
        serviceMetadata.setEndpoint(new ServiceEndpoint(advertisedHost, advertisedPort));
        serviceMetadata.setServiceInterface(exportConfig.getInterfaceClass());
        serviceMetadata.setAttributes(exportConfig.getAttributes());
        serviceRepository.validateExport(serviceMetadata, exportConfig.getServiceImpl());
        ServiceMetadata previousMetadata = exportedServices.stream()
                .filter(existing -> existing.serviceKey().equals(serviceMetadata.serviceKey()))
                .findFirst()
                .orElse(null);
        String serviceKey = serviceMetadata.serviceKey();
        Object previousImplementation = previousMetadata == null
                ? null : exportedImplementations.get(serviceKey);
        if (started) {
            if (previousMetadata != null && !metadataEquivalent(previousMetadata, serviceMetadata)) {
                if (previousMetadata.equals(serviceMetadata)) {
                    serviceRegistry.unregister(previousMetadata);
                    try {
                        serviceRepository.export(serviceMetadata, exportConfig.getServiceImpl());
                        serviceRegistry.register(serviceMetadata);
                    } catch (RuntimeException e) {
                        try {
                            serviceRegistry.unregister(serviceMetadata);
                            restoreExport(previousMetadata, previousImplementation, e);
                        } catch (RuntimeException rollbackFailure) {
                            e.addSuppressed(rollbackFailure);
                        }
                        throw e;
                    }
                } else {
                    serviceRegistry.register(serviceMetadata);
                    try {
                        serviceRepository.export(serviceMetadata, exportConfig.getServiceImpl());
                    } catch (RuntimeException e) {
                        try {
                            serviceRegistry.unregister(serviceMetadata);
                            restoreExport(previousMetadata, previousImplementation, e);
                        } catch (RuntimeException rollbackFailure) {
                            e.addSuppressed(rollbackFailure);
                        }
                        throw e;
                    }
                    try {
                        serviceRegistry.unregister(previousMetadata);
                    } catch (RuntimeException e) {
                        try {
                            serviceRegistry.unregister(serviceMetadata);
                            restoreExport(previousMetadata, previousImplementation, e);
                        } catch (RuntimeException rollbackFailure) {
                            e.addSuppressed(rollbackFailure);
                        }
                        throw e;
                    }
                }
            } else {
                serviceRegistry.register(serviceMetadata);
                try {
                    serviceRepository.export(serviceMetadata, exportConfig.getServiceImpl());
                } catch (RuntimeException e) {
                    try {
                        serviceRegistry.unregister(serviceMetadata);
                        restoreExport(previousMetadata, previousImplementation, e);
                    } catch (RuntimeException rollbackFailure) {
                        e.addSuppressed(rollbackFailure);
                    }
                    throw e;
                }
            }
        } else {
            serviceRepository.export(serviceMetadata, exportConfig.getServiceImpl());
        }
        exportedServices.removeIf(existing -> existing.serviceKey().equals(serviceKey));
        exportedServices.add(serviceMetadata);
        exportedImplementations.put(serviceKey, exportConfig.getServiceImpl());
        return this;
    }

    /**
     * Exports a provider whose implementation class carries
     * {@link CourierProvider}. This is an explicit opt-in convenience API; it
     * does not scan the classpath.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public YunqiCourierBootstrap exportAnnotated(Object serviceProvider) {
        Objects.requireNonNull(serviceProvider, "serviceProvider");
        CourierProvider annotation = serviceProvider.getClass().getAnnotation(CourierProvider.class);
        if (annotation == null) {
            throw new IllegalArgumentException("Provider class must carry @CourierProvider: "
                    + serviceProvider.getClass().getName());
        }
        Class<?> interfaceClass = annotation.interfaceClass();
        if (!interfaceClass.isInterface() || !interfaceClass.isInstance(serviceProvider)) {
            throw new IllegalArgumentException("@CourierProvider interfaceClass must be implemented by provider: "
                    + interfaceClass.getName());
        }
        ServiceExportConfig exportConfig = new ServiceExportConfig(interfaceClass, serviceProvider)
                .group(annotation.group())
                .version(annotation.version());
        return export(exportConfig);
    }

    /**
     * Injects fields annotated with {@link CourierReference}. Fields are
     * resolved across the target class hierarchy and must be non-static and
     * non-final. Call this after the bootstrap has been configured; references
     * retain the bootstrap's current network client by design.
     */
    public void injectReferences(Object target) {
        Objects.requireNonNull(target, "target");
        for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                CourierReference annotation = field.getAnnotation(CourierReference.class);
                if (annotation == null) {
                    continue;
                }
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                    throw new IllegalArgumentException("@CourierReference field must not be static or final: "
                            + field);
                }
                Class<?> interfaceClass = annotation.interfaceClass() == void.class
                        ? field.getType() : annotation.interfaceClass();
                if (!interfaceClass.isInterface() || !field.getType().isAssignableFrom(interfaceClass)) {
                    throw new IllegalArgumentException("@CourierReference field type must accept interface: "
                            + field);
                }
                ServiceReferenceConfig<?> referenceConfig = ServiceReferenceConfig.of(interfaceClass)
                        .group(annotation.group())
                        .version(annotation.version());
                if (annotation.timeoutMillis() != -1) {
                    referenceConfig.timeoutMillis(annotation.timeoutMillis());
                }
                Object proxy = refer(referenceConfig);
                try {
                    if (!field.trySetAccessible()) {
                        throw new IllegalAccessException("Field is not accessible");
                    }
                    field.set(target, proxy);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Inject @CourierReference failed, field=" + field, e);
                }
            }
        }
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        if (componentsClosed) {
            initComponents();
        }
        try {
            networkServer.start(courierConfig.getHost(), courierConfig.getPort(),
                    new DefaultRequestProcessor(serviceRepository), courierConfig.getMaxFrameLength(),
                    requestAccessController);
        } catch (RuntimeException e) {
            cleanupAfterStartFailure(e);
            throw e;
        }
        List<ServiceMetadata> registered = new ArrayList<>();
        try {
            for (ServiceMetadata serviceMetadata : exportedServices) {
                serviceRegistry.register(serviceMetadata);
                registered.add(serviceMetadata);
            }
            started = true;
            startLeaseRenewal();
            startHealthServer();
        } catch (RuntimeException e) {
            for (ServiceMetadata serviceMetadata : registered) {
                try {
                    serviceRegistry.unregister(serviceMetadata);
                } catch (RuntimeException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
            }
            cleanupAfterStartFailure(e);
            throw e;
        }
    }

    public synchronized void stop() {
        RuntimeException failure = null;
        boolean closeRegistry = !componentsClosed;
        if (started) {
            for (ServiceMetadata serviceMetadata : exportedServices) {
                try {
                    serviceRegistry.unregister(serviceMetadata);
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = new RuntimeException("Failed to unregister one or more services");
                    }
                    failure.addSuppressed(e);
                }
            }
        }
        // Fail application-facing asynchronous calls before waiting for the
        // server's graceful business-queue drain.
        cancelAsyncCalls("YunQi-Courier bootstrap stopped");
        try {
            stopLeaseRenewal();
            stopHealthServer();
            networkServer.stop();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        } finally {
            try {
                networkClient.close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            if (closeRegistry) {
                try {
                    closeReferences();
                    serviceRegistry.close();
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (asyncExecutor != null) {
                asyncExecutor.shutdownNow();
                asyncExecutor = null;
            }
            started = false;
            componentsClosed = true;
        }
        if (failure != null) {
            throw failure;
        }
    }

    public boolean isStarted() {
        return started;
    }

    /** Returns the bound management port, or {@code -1} when management is disabled. */
    public synchronized int managementPort() {
        return healthServer == null ? -1 : healthServer.port();
    }

    public InvocationMetricsSnapshot metrics() {
        return invocationMetrics.snapshot();
    }

    public YunqiCourierBootstrap registerMetricsExporter(InvocationMetricsExporter exporter) {
        invocationMetrics.registerExporter(exporter);
        return this;
    }

    public YunqiCourierBootstrap unregisterMetricsExporter(InvocationMetricsExporter exporter) {
        invocationMetrics.unregisterExporter(exporter);
        return this;
    }

    public YunqiCourierBootstrap registerMetricsListener(InvocationMetricsListener listener) {
        invocationMetrics.registerListener(listener);
        return this;
    }

    public YunqiCourierBootstrap unregisterMetricsListener(InvocationMetricsListener listener) {
        invocationMetrics.unregisterListener(listener);
        return this;
    }

    /** Reloads configured key/trust stores without rebuilding the bootstrap. */
    public synchronized void reloadTlsContext() {
        networkClient.reloadTlsContext();
        networkServer.reloadTlsContext();
    }

    public void close() {
        stop();
    }

    public synchronized <T> T refer(ServiceReferenceConfig<T> referenceConfig) {
        Objects.requireNonNull(referenceConfig, "referenceConfig");
        ProxyFactory proxyFactory = ServiceProviderLoader.load(ProxyFactory.class, courierConfig.getProxying());
        ServiceInvocationHandler<T> handler = new ServiceInvocationHandler<>(referenceConfig, courierConfig,
                serviceRegistry, trafficLoadBalancer, networkClient, invocationMetrics);
        references.add(handler);
        return proxyFactory.createProxy(referenceConfig.getInterfaceClass(), handler);
    }

    public synchronized <T> AsyncServiceReference<T> referAsync(Class<T> interfaceClass) {
        return referAsync(ServiceReferenceConfig.of(interfaceClass));
    }

    /**
     * Returns an asynchronous facade backed by the same proxy handler and
     * resilience policy as synchronous references. Work is dispatched to the
     * per-call virtual threads so the caller never blocks on network I/O.
     */
    public synchronized <T> AsyncServiceReference<T> referAsync(ServiceReferenceConfig<T> referenceConfig) {
        Objects.requireNonNull(referenceConfig, "referenceConfig");
        ServiceInvocationHandler<T> handler = new ServiceInvocationHandler<>(referenceConfig, courierConfig,
                serviceRegistry, trafficLoadBalancer, networkClient, invocationMetrics);
        references.add(handler);
        return new AsyncServiceReference<>() {
            @Override
            public <R> CompletableFuture<R> invoke(String methodName, Object... arguments) {
                return submitAsync(referenceConfig, handler, null, methodName, arguments);
            }

            @Override
            public <R> CompletableFuture<R> invokeExact(Method method, Object... arguments) {
                Objects.requireNonNull(method, "method");
                Class<?> declaringClass = method.getDeclaringClass();
                if (declaringClass == Object.class || Modifier.isStatic(method.getModifiers()) || method.isSynthetic()
                        || !declaringClass.isAssignableFrom(referenceConfig.getInterfaceClass())) {
                    throw new IllegalArgumentException("Method is not part of the referenced interface: " + method);
                }
                return submitAsync(referenceConfig, handler, method, null, arguments);
            }
        };
    }

    private synchronized <T, R> CompletableFuture<R> submitAsync(ServiceReferenceConfig<T> referenceConfig,
                                                                 ServiceInvocationHandler<T> handler,
                                                                 Method exactMethod,
                                                                 String methodName,
                                                                 Object[] arguments) {
        Object[] capturedArguments = arguments == null ? new Object[0] : arguments.clone();
        Map<String, String> capturedAttachments = RequestContext.getAttachments();
        String capturedTraceId = RequestContext.getTraceId();
        CompletableFuture<R> result = new CompletableFuture<>();
        if (componentsClosed || asyncExecutor == null) {
            result.completeExceptionally(new RpcException("Async invocation rejected because bootstrap is stopped"));
            return result;
        }
        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                RequestContext.setAttachments(capturedAttachments);
                RequestContext.setTraceId(capturedTraceId);
                Method method = exactMethod == null
                        ? resolveMethod(referenceConfig.getInterfaceClass(), methodName, capturedArguments).method()
                        : exactMethod;
                @SuppressWarnings("unchecked")
                R value = (R) handler.invoke(null, method, capturedArguments);
                result.complete(value);
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            } finally {
                RequestContext.clear();
            }
            return null;
        });
        AsyncCall call = new AsyncCall(task, result);
        asyncCalls.add(call);
        // FutureTask.cancel(true) interrupts a blocking handler.invoke call. The
        // Netty response future observes that interruption and removes its pending
        // request, so cancelling the facade does not strand transport resources.
        result.whenComplete((ignored, failure) -> {
            asyncCalls.remove(call);
            if (result.isCancelled()) {
                task.cancel(true);
            }
        });
        Executor executor = asyncExecutor;
        try {
            executor.execute(task);
        } catch (RejectedExecutionException rejected) {
            asyncCalls.remove(call);
            task.cancel(false);
            result.completeExceptionally(new RpcException("Async invocation rejected during shutdown", rejected));
        }
        return result;
    }

    private MethodResolution resolveMethod(Class<?> interfaceClass, String methodName, Object[] arguments) {
        if (methodName == null || methodName.isBlank()) {
            throw new IllegalArgumentException("methodName must not be blank");
        }
        Object[] actual = arguments == null ? new Object[0] : arguments;
        List<Method> candidates = new ArrayList<>();
        for (Method method : interfaceClass.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic()
                    || !method.getName().equals(methodName) || method.getParameterCount() != actual.length) {
                continue;
            }
            boolean compatible = true;
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                if ((actual[i] == null && types[i].isPrimitive())
                        || (actual[i] != null && !box(types[i]).isInstance(actual[i]))) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) {
                candidates.add(method);
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("No matching method, interface=" + interfaceClass.getName()
                    + ", method=" + methodName);
        }
        int bestMatchScore = candidates.stream()
                .mapToInt(candidate -> matchScore(candidate, actual))
                .max()
                .orElse(0);
        candidates = candidates.stream()
                .filter(candidate -> matchScore(candidate, actual) == bestMatchScore)
                .toList();
        // Pick the most specific overload. A null argument can match multiple
        // unrelated reference types; rejecting that case is safer than making
        // invocation depend on reflection's method enumeration order.
        List<Method> mostSpecific = new ArrayList<>();
        for (Method candidate : candidates) {
            boolean dominated = false;
            for (Method other : candidates) {
                if (candidate.equals(other)) {
                    continue;
                }
                if (isMoreSpecific(other, candidate)) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) {
                mostSpecific.add(candidate);
            }
        }
        if (mostSpecific.size() != 1) {
            throw new IllegalArgumentException("Ambiguous method overload, interface=" + interfaceClass.getName()
                    + ", method=" + methodName + ", candidates=" + mostSpecific);
        }
        return new MethodResolution(mostSpecific.get(0));
    }

    private int matchScore(Method method, Object[] arguments) {
        int score = 0;
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            Object argument = arguments[i];
            if (argument == null) {
                continue;
            }
            Class<?> argumentType = argument.getClass();
            Class<?> parameterType = parameterTypes[i];
            if (!parameterType.isPrimitive() && parameterType.equals(argumentType)) {
                score += 100;
            } else if (parameterType.isPrimitive() && box(parameterType).equals(argumentType)) {
                score += 90;
            } else if (parameterType.isAssignableFrom(argumentType)) {
                score += 50;
            }
        }
        return score;
    }

    private boolean isMoreSpecific(Method candidate, Method other) {
        Class<?>[] candidateTypes = candidate.getParameterTypes();
        Class<?>[] otherTypes = other.getParameterTypes();
        boolean strict = false;
        for (int i = 0; i < candidateTypes.length; i++) {
            Class<?> candidateType = box(candidateTypes[i]);
            Class<?> otherType = box(otherTypes[i]);
            if (!otherType.isAssignableFrom(candidateType)) {
                return false;
            }
            if (!candidateType.equals(otherType)) {
                strict = true;
            }
        }
        return strict;
    }

    private Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private record MethodResolution(Method method) {
    }

    private record AsyncCall(FutureTask<Void> task, CompletableFuture<?> result) {
    }

    private void initComponents() {
        ServiceRegistry newRegistry = null;
        NetworkClient newNetworkClient = null;
        NetworkServer newNetworkServer = null;
        try {
            newRegistry = ServiceProviderLoader.newInstance(ServiceRegistry.class, courierConfig.getRegistry());
            newRegistry.configure(courierConfig);
            TrafficLoadBalancer newLoadBalancer = ServiceProviderLoader.newInstance(TrafficLoadBalancer.class,
                    courierConfig.getLoadBalancing());
            newNetworkClient = ServiceProviderLoader.newInstance(NetworkClient.class, courierConfig.getNetwork());
            newNetworkServer = ServiceProviderLoader.newInstance(NetworkServer.class, courierConfig.getNetwork());
            newNetworkClient.configure(courierConfig);
            newNetworkServer.configure(courierConfig);
            this.serviceRegistry = newRegistry;
            this.trafficLoadBalancer = newLoadBalancer;
            this.networkClient = newNetworkClient;
            this.networkServer = newNetworkServer;
            this.requestAccessController = configuredAccessController == null
                    ? new DefaultRequestAccessController(courierConfig) : configuredAccessController;
            this.asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
            this.componentsClosed = false;
        } catch (RuntimeException e) {
            if (newNetworkServer != null) {
                try {
                    newNetworkServer.stop();
                } catch (RuntimeException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            if (newNetworkClient != null) {
                try {
                    newNetworkClient.close();
                } catch (RuntimeException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            if (newRegistry != null) {
                try {
                    newRegistry.close();
                } catch (RuntimeException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            throw e;
        }
    }

    private void closeRegistry(RuntimeException failure) {
        try {
            serviceRegistry.close();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private boolean metadataEquivalent(ServiceMetadata left, ServiceMetadata right) {
        return left.equals(right) && left.getAttributes().equals(right.getAttributes());
    }

    private void restoreExport(ServiceMetadata previousMetadata, Object previousImplementation,
                               RuntimeException failure) {
        if (previousMetadata == null || previousImplementation == null) {
            return;
        }
        try {
            serviceRepository.export(previousMetadata, previousImplementation);
            serviceRegistry.register(previousMetadata);
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private void cleanupAfterStartFailure(RuntimeException failure) {
        stopLeaseRenewal();
        stopHealthServer();
        closeReferences();
        cancelAsyncCalls("YunQi-Courier bootstrap failed to start");
        if (asyncExecutor != null) {
            asyncExecutor.shutdownNow();
            asyncExecutor = null;
        }
        try {
            networkServer.stop();
        } catch (RuntimeException stopFailure) {
            failure.addSuppressed(stopFailure);
        }
        try {
            networkClient.close();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
        closeRegistry(failure);
        componentsClosed = true;
        started = false;
    }

    private void cancelAsyncCalls(String reason) {
        for (AsyncCall call : asyncCalls) {
            call.task().cancel(true);
            call.result().completeExceptionally(new RpcException(reason));
        }
        asyncCalls.clear();
    }

    private void closeReferences() {
        for (ServiceInvocationHandler<?> reference : references) {
            try {
                reference.close();
            } catch (RuntimeException ignored) {
                // Best-effort watch cleanup while other resources are released.
            }
        }
        references.clear();
    }

    private void startLeaseRenewal() {
        stopLeaseRenewal();
        leaseRenewer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "yunqi-courier-registry-renewer");
            thread.setDaemon(true);
            return thread;
        });
        long period = Math.max(100L, courierConfig.getRegistryLeaseTtlMillis() / 3L);
        leaseRenewalTask = leaseRenewer.scheduleWithFixedDelay(() -> {
            for (ServiceMetadata serviceMetadata : exportedServices) {
                try {
                    serviceRegistry.renew(serviceMetadata);
                } catch (RuntimeException failure) {
                    LOGGER.log(System.Logger.Level.WARNING,
                            "Registry lease renewal failed, service=" + serviceMetadata.serviceKey(), failure);
                }
            }
        }, period, period, TimeUnit.MILLISECONDS);
    }

    private void stopLeaseRenewal() {
        if (leaseRenewalTask != null) {
            leaseRenewalTask.cancel(false);
            leaseRenewalTask = null;
        }
        if (leaseRenewer != null) {
            leaseRenewer.shutdownNow();
            leaseRenewer = null;
        }
    }

    private void startHealthServer() {
        if (courierConfig.isManagementEnabled()) {
            healthServer = HealthServer.start(courierConfig.getManagementHost(),
                    courierConfig.getManagementPort(), this::isStarted);
        }
    }

    private void stopHealthServer() {
        if (healthServer != null) {
            healthServer.close();
            healthServer = null;
        }
    }

    private boolean isWildcardHost(String host) {
        return "0.0.0.0".equals(host) || "::".equals(host) || "[::]".equals(host);
    }

}
