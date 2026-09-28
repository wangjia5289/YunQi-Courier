package yunqi.courier.kernel.spi.registry;

import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.common.service.ServiceMetadata;

import java.util.List;
import java.util.function.Consumer;

public interface ServiceRegistry {

    /** Configure or reopen the registry connection for one bootstrap. */
    default void configure(CourierConfig config) {
    }

    void register(ServiceMetadata serviceMetadata);

    void unregister(ServiceMetadata serviceMetadata);

    List<ServiceMetadata> discover(String serviceKey);

    /** Renews the provider lease. Implementations may ignore this for static registrations. */
    default void renew(ServiceMetadata serviceMetadata) {
    }

    /** Subscribes to provider changes. The returned handle is safe to close repeatedly. */
    default AutoCloseable watch(String serviceKey, RegistryListener listener) {
        return () -> {
        };
    }

    /** Updates a provider health marker when an active probe is available. */
    default void updateHealth(ServiceMetadata serviceMetadata, boolean healthy) {
    }

    /** Release registry connections, watches and lease renewal tasks. */
    default void close() {
    }
}
