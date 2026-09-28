package yunqi.courier.registry.zookeeper;

import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.client.ZKClientConfig;
import org.junit.jupiter.api.Test;
import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.common.service.ServiceEndpoint;
import yunqi.courier.common.service.ServiceMetadata;
import yunqi.courier.kernel.spi.core.ServiceProviderLoader;
import yunqi.courier.kernel.spi.registry.ServiceRegistry;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZooKeeperServiceRegistryTest {

    @Test
    void sharesEphemeralRegistrationsAndWatchUpdates() throws Exception {
        try (TestingServer server = new TestingServer()) {
            CourierConfig config = config(server);
            ZookeeperServiceRegistry provider = new ZookeeperServiceRegistry();
            ZookeeperServiceRegistry consumer = new ZookeeperServiceRegistry();
            provider.configure(config);
            consumer.configure(config);
            ServiceMetadata metadata = metadata("127.0.0.1", 20_881);
            CopyOnWriteArrayList<Integer> sizes = new CopyOnWriteArrayList<>();
            AutoCloseable watch = consumer.watch(metadata.serviceKey(), (key, providers) -> sizes.add(providers.size()));
            try {
                provider.register(metadata);
                await(() -> consumer.discover(metadata.serviceKey()).size() == 1);
                assertEquals("127.0.0.1", consumer.discover(metadata.serviceKey()).getFirst().getEndpoint().getHost());

                provider.updateHealth(metadata, false);
                await(() -> "false".equals(consumer.discover(metadata.serviceKey()).getFirst()
                        .getAttributes().get("healthy")));
                assertFalse(sizes.isEmpty());

                provider.close();
                await(() -> consumer.discover(metadata.serviceKey()).isEmpty());
            } finally {
                watch.close();
                consumer.close();
                provider.close();
            }
        }
    }

    @Test
    void recreatesProviderAfterSessionExpiration() throws Exception {
        try (TestingServer server = new TestingServer()) {
            CourierConfig config = config(server);
            config.setRegistrySessionTimeoutMillis(1_000);
            config.setRegistryRetryBaseSleepMillis(100);
            config.setRegistryRetryMaxRetries(100);
            ZookeeperServiceRegistry provider = new ZookeeperServiceRegistry();
            ServiceMetadata metadata = metadata("127.0.0.1", 20_882);
            provider.configure(config);
            provider.register(metadata);
            try {
                server.stop();
                Thread.sleep(2_500);
                server.restart();
                await(() -> {
                    try {
                        return provider.discover(metadata.serviceKey()).size() == 1;
                    } catch (RuntimeException unavailable) {
                        return false;
                    }
                }, Duration.ofSeconds(12));
            } finally {
                provider.close();
            }
        }
    }

    @Test
    void rejectsMissingConnectString() {
        ZookeeperServiceRegistry registry = new ZookeeperServiceRegistry();
        CourierConfig config = new CourierConfig();
        config.setRegistryNamespace("tests");
        try {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> registry.configure(config));
        } finally {
            registry.close();
        }
    }

    @Test
    void configuresZooKeeperHostnameVerification() {
        CourierConfig config = new CourierConfig();
        config.setRegistryTlsEnabled(true);
        config.setRegistryTlsTrustStorePath("trust.p12");
        config.setRegistryTlsTrustStorePassword("changeit");

        ZKClientConfig clientConfig = ZookeeperServiceRegistry.tlsClientConfig(config);
        assertEquals("true", clientConfig.getProperty("zookeeper.ssl.hostnameVerification"));
        assertEquals("TLSv1.3,TLSv1.2", clientConfig.getProperty("zookeeper.ssl.enabledProtocols"));
        assertEquals("TLSv1.3", clientConfig.getProperty("zookeeper.ssl.protocol"));

        config.setRegistryTlsHostnameVerificationEnabled(false);
        clientConfig = ZookeeperServiceRegistry.tlsClientConfig(config);
        assertEquals("false", clientConfig.getProperty("zookeeper.ssl.hostnameVerification"));
    }

    @Test
    void isAvailableThroughTheRegistrySpi() {
        ServiceRegistry registry = ServiceProviderLoader.newInstance(ServiceRegistry.class, "zookeeper");
        assertTrue(registry instanceof ZookeeperServiceRegistry);
        registry.close();
    }

    private static CourierConfig config(TestingServer server) {
        CourierConfig config = new CourierConfig();
        config.setRegistryAddress(server.getConnectString());
        config.setRegistryNamespace("tests-" + System.nanoTime());
        config.setRegistryConnectTimeoutMillis(5_000);
        config.setRegistrySessionTimeoutMillis(5_000);
        return config;
    }

    private static ServiceMetadata metadata(String host, int port) {
        ServiceMetadata metadata = new ServiceMetadata();
        metadata.setServiceName("demo.Echo");
        metadata.setGroup("default");
        metadata.setVersion("1.0.0");
        metadata.setEndpoint(new ServiceEndpoint(host, port));
        metadata.setAttributes(Map.of("weight", "2"));
        return metadata;
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        await(condition, Duration.ofSeconds(5));
    }

    private static void await(java.util.function.BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "condition was not met before timeout");
    }
}
