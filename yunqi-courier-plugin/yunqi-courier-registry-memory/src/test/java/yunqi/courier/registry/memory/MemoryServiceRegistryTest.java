package yunqi.courier.registry.memory;

import org.junit.jupiter.api.Test;
import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.common.service.ServiceEndpoint;
import yunqi.courier.common.service.ServiceMetadata;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryServiceRegistryTest {

    @Test
    void reconfigureClearsOwnedServicesAndWatches() {
        MemoryServiceRegistry registry = new MemoryServiceRegistry();
        CourierConfig config = new CourierConfig();
        registry.configure(config);
        ServiceMetadata metadata = new ServiceMetadata();
        metadata.setServiceName("memory.reconfigure");
        metadata.setEndpoint(new ServiceEndpoint("127.0.0.1", 20880));
        registry.register(metadata);
        assertTrue(!registry.discover(metadata.serviceKey()).isEmpty());

        registry.configure(config);
        try {
            assertTrue(registry.discover(metadata.serviceKey()).isEmpty());
        } finally {
            registry.close();
        }
    }
}
