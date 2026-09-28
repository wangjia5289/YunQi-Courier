package yunqi.courier.registry.memory;

import yunqi.courier.common.service.ServiceMetadata;
import yunqi.courier.kernel.spi.core.SPI;
import yunqi.courier.kernel.spi.registry.ServiceRegistry;
import yunqi.courier.kernel.spi.registry.RegistryListener;
import yunqi.courier.common.config.CourierConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;

@SPI("memory")
public class MemoryServiceRegistry implements ServiceRegistry {

    private static final Map<String, CopyOnWriteArrayList<ServiceMetadata>> SERVICE_MAP = new ConcurrentHashMap<>();

    private static final Map<String, Map<ServiceMetadata, Long>> LEASES = new ConcurrentHashMap<>();

    private static final Map<String, CopyOnWriteArrayList<RegistryListener>> WATCHERS = new ConcurrentHashMap<>();

    private static final java.util.concurrent.ScheduledExecutorService LEASE_CLEANER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "yunqi-courier-memory-registry-lease");
        thread.setDaemon(true);
        return thread;
    });

    static {
        LEASE_CLEANER.scheduleWithFixedDelay(MemoryServiceRegistry::expireLeases, 100, 100, TimeUnit.MILLISECONDS);
    }

    private volatile int leaseTtlMillis = 30_000;
    private final AtomicBoolean closed = new AtomicBoolean();

    private final CopyOnWriteArrayList<WatchRegistration> registrations = new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<ServiceMetadata> ownedServices = new CopyOnWriteArrayList<>();

    @Override
    public synchronized void configure(CourierConfig config) {
        for (ServiceMetadata metadata : List.copyOf(ownedServices)) {
            unregister(metadata);
        }
        for (WatchRegistration registration : List.copyOf(registrations)) {
            closeWatch(registration);
        }
        ownedServices.clear();
        registrations.clear();
        closed.set(false);
        leaseTtlMillis = config.getRegistryLeaseTtlMillis();
    }

    @Override
    public synchronized void register(ServiceMetadata serviceMetadata) {
        if (closed.get()) {
            throw new IllegalStateException("Registry is closed");
        }
        if (serviceMetadata == null) {
            throw new IllegalArgumentException("serviceMetadata must not be null");
        }
        serviceMetadata.validate();
        ServiceMetadata snapshot = serviceMetadata.snapshot();
        CopyOnWriteArrayList<ServiceMetadata> providers = SERVICE_MAP.computeIfAbsent(snapshot.serviceKey(),
                key -> new CopyOnWriteArrayList<>());
        providers.remove(snapshot);
        providers.add(snapshot);
        LEASES.computeIfAbsent(snapshot.serviceKey(), key -> new ConcurrentHashMap<>())
                .put(snapshot, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(leaseTtlMillis));
        ownedServices.removeIf(existing -> existing.equals(snapshot));
        ownedServices.add(snapshot);
        notifyWatchers(snapshot.serviceKey());
    }

    @Override
    public synchronized void unregister(ServiceMetadata serviceMetadata) {
        if (serviceMetadata == null) {
            return;
        }
        List<ServiceMetadata> serviceMetadataList = SERVICE_MAP.get(serviceMetadata.serviceKey());
        if (serviceMetadataList != null) {
            serviceMetadataList.remove(serviceMetadata);
            Map<ServiceMetadata, Long> leases = LEASES.get(serviceMetadata.serviceKey());
            if (leases != null) {
                leases.remove(serviceMetadata);
                if (leases.isEmpty()) {
                    LEASES.remove(serviceMetadata.serviceKey(), leases);
                }
            }
            if (serviceMetadataList.isEmpty()) {
                SERVICE_MAP.remove(serviceMetadata.serviceKey(), serviceMetadataList);
            }
            ownedServices.remove(serviceMetadata);
            notifyWatchers(serviceMetadata.serviceKey());
        }
    }

    @Override
    public List<ServiceMetadata> discover(String serviceKey) {
        if (serviceKey == null || serviceKey.isBlank()) {
            return List.of();
        }
        long now = System.nanoTime();
        Map<ServiceMetadata, Long> leases = LEASES.get(serviceKey);
        return SERVICE_MAP.getOrDefault(serviceKey, new CopyOnWriteArrayList<>()).stream()
                .filter(metadata -> leases == null || leases.getOrDefault(metadata, 0L) > now)
                .map(ServiceMetadata::snapshot)
                .toList();
    }

    @Override
    public synchronized void renew(ServiceMetadata serviceMetadata) {
        if (serviceMetadata == null) {
            return;
        }
        Map<ServiceMetadata, Long> leases = LEASES.get(serviceMetadata.serviceKey());
        if (leases != null) {
            leases.replace(serviceMetadata, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(leaseTtlMillis));
        }
    }

    @Override
    public synchronized AutoCloseable watch(String serviceKey, RegistryListener listener) {
        if (closed.get() || serviceKey == null || serviceKey.isBlank() || listener == null) {
            return () -> {
            };
        }
        CopyOnWriteArrayList<RegistryListener> listeners = WATCHERS.computeIfAbsent(serviceKey,
                key -> new CopyOnWriteArrayList<>());
        listeners.addIfAbsent(listener);
        WatchRegistration registration = new WatchRegistration(serviceKey, listener);
        registrations.add(registration);
        try {
            listener.onChange(serviceKey, discover(serviceKey));
        } catch (RuntimeException ignored) {
            // Initial delivery follows the same isolation contract as later
            // notifications; a consumer callback must not break subscription.
        }
        return () -> {
            closeWatch(registration);
        };
    }

    @Override
    public synchronized void updateHealth(ServiceMetadata serviceMetadata, boolean healthy) {
        if (serviceMetadata == null) {
            return;
        }
        List<ServiceMetadata> providers = SERVICE_MAP.get(serviceMetadata.serviceKey());
        if (providers == null) {
            return;
        }
        for (int i = 0; i < providers.size(); i++) {
            ServiceMetadata current = providers.get(i);
            if (current.equals(serviceMetadata)) {
                ServiceMetadata updated = current.snapshot();
                updated.setAttributes(withHealth(updated.getAttributes(), healthy));
                providers.set(i, updated);
                notifyWatchers(current.serviceKey());
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed.compareAndSet(false, true)) {
            ownedServices.forEach(this::unregister);
            ownedServices.clear();
            registrations.forEach(this::closeWatch);
            registrations.clear();
        }
    }

    private static void expireLeases() {
        long now = System.nanoTime();
        LEASES.forEach((serviceKey, leases) -> leases.forEach((metadata, expiry) -> {
            if (expiry <= now) {
                leases.remove(metadata, expiry);
                CopyOnWriteArrayList<ServiceMetadata> providers = SERVICE_MAP.get(serviceKey);
                if (providers != null) {
                    providers.remove(metadata);
                    if (providers.isEmpty()) {
                        SERVICE_MAP.remove(serviceKey, providers);
                    }
                }
                notifyWatchers(serviceKey);
            }
        }));
    }

    private static void notifyWatchers(String serviceKey) {
        List<ServiceMetadata> snapshot = discoverStatic(serviceKey);
        for (RegistryListener listener : WATCHERS.getOrDefault(serviceKey, new CopyOnWriteArrayList<>())) {
            try {
                listener.onChange(serviceKey, snapshot);
            } catch (RuntimeException ignored) {
                // A broken watch callback must not affect registry operations.
            }
        }
    }

    private static List<ServiceMetadata> discoverStatic(String serviceKey) {
        if (serviceKey == null || serviceKey.isBlank()) {
            return List.of();
        }
        return SERVICE_MAP.getOrDefault(serviceKey, new CopyOnWriteArrayList<>()).stream()
                .map(ServiceMetadata::snapshot)
                .toList();
    }

    private Map<String, String> withHealth(Map<String, String> attributes, boolean healthy) {
        Map<String, String> updated = new java.util.HashMap<>(attributes);
        updated.put(yunqi.courier.common.constant.CourierConstants.SERVICE_ATTRIBUTE_HEALTHY,
                Boolean.toString(healthy));
        return updated;
    }

    private synchronized void closeWatch(WatchRegistration registration) {
        if (!registrations.remove(registration)) {
            return;
        }
        CopyOnWriteArrayList<RegistryListener> listeners = WATCHERS.get(registration.serviceKey());
        if (listeners != null) {
            listeners.remove(registration.listener());
            if (listeners.isEmpty()) {
                WATCHERS.remove(registration.serviceKey(), listeners);
            }
        }
    }

    private record WatchRegistration(String serviceKey, RegistryListener listener) {
    }
}
