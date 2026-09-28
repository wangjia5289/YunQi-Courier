package yunqi.courier.registry.file;

import org.junit.jupiter.api.Test;
import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.common.service.ServiceEndpoint;
import yunqi.courier.common.service.ServiceMetadata;

import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileServiceRegistryTest {

    @Test
    void shouldShareRegistrationAndExpireLeases() throws Exception {
        var path = Files.createTempFile("yunqi-courier-registry", ".db");
        CourierConfig config = new CourierConfig();
        config.setRegistryFilePath(path.toString());
        config.setRegistryLeaseTtlMillis(1_500);
        FileServiceRegistry registry = new FileServiceRegistry();
        registry.configure(config);
        ServiceMetadata metadata = new ServiceMetadata();
        metadata.setServiceName("demo.Echo");
        metadata.setGroup("default");
        metadata.setVersion("1.0.0");
        metadata.setEndpoint(new ServiceEndpoint("127.0.0.1", 20880));
        AtomicInteger changes = new AtomicInteger();
        AutoCloseable watch = registry.watch(metadata.serviceKey(), (key, providers) -> changes.incrementAndGet());
        registry.register(metadata);
        assertEquals(1, registry.discover(metadata.serviceKey()).size());
        assertTrue(changes.get() >= 2);
        registry.renew(metadata);
        Thread.sleep(700);
        assertFalse(registry.discover(metadata.serviceKey()).isEmpty());
        Thread.sleep(1_200);
        assertTrue(registry.discover(metadata.serviceKey()).isEmpty());
        registry.unregister(metadata);
        assertTrue(registry.discover(metadata.serviceKey()).isEmpty());
        watch.close();
        registry.close();
    }

    @Test
    void shouldNotMutateRegistryAfterClose() throws Exception {
        var path = Files.createTempFile("yunqi-courier-registry-closed", ".db");
        CourierConfig config = new CourierConfig();
        config.setRegistryFilePath(path.toString());
        FileServiceRegistry registry = new FileServiceRegistry();
        registry.configure(config);
        ServiceMetadata metadata = new ServiceMetadata();
        metadata.setServiceName("demo.Closed");
        metadata.setEndpoint(new ServiceEndpoint("127.0.0.1", 20880));
        registry.register(metadata);

        registry.close();
        registry.renew(metadata);
        registry.updateHealth(metadata, false);
        registry.unregister(metadata);

        FileServiceRegistry reopened = new FileServiceRegistry();
        reopened.configure(config);
        try {
            assertTrue(reopened.discover(metadata.serviceKey()).isEmpty());
        } finally {
            reopened.close();
        }
        assertTrue(registry.discover(metadata.serviceKey()).isEmpty());
    }
}
