package yunqi.courier.network.netty;

import yunqi.courier.api.annotation.CourierProvider;
import yunqi.courier.api.annotation.CourierReference;
import yunqi.courier.api.bootstrap.ServiceExportConfig;
import yunqi.courier.api.bootstrap.ServiceReferenceConfig;
import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.kernel.YunqiCourierBootstrap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiBootstrapE2eTest {

    @Test
    void shouldDiscoverProviderFromAnotherBootstrap() {
        CourierConfig providerConfig = new CourierConfig();
        providerConfig.setPort(20992);
        YunqiCourierBootstrap provider = new YunqiCourierBootstrap(providerConfig);

        CourierConfig consumerConfig = new CourierConfig();
        consumerConfig.setPort(20993);
        YunqiCourierBootstrap consumer = new YunqiCourierBootstrap(consumerConfig);
        try {
            provider.export(ServiceExportConfig.of(GreetingService.class, new GreetingServiceImpl()));
            provider.start();

            GreetingService greeting = consumer.refer(ServiceReferenceConfig.of(GreetingService.class));
            assertEquals("hello, courier", greeting.greet("courier"));
        } finally {
            consumer.close();
            provider.close();
        }
    }

    @Test
    void shouldBeRestartable() {
        CourierConfig config = new CourierConfig();
        config.setPort(20994);
        YunqiCourierBootstrap courier = new YunqiCourierBootstrap(config);
        try {
            courier.export(ServiceExportConfig.of(GreetingService.class, new GreetingServiceImpl()));
            courier.start();
            courier.stop();
            courier.start();
            assertTrue(courier.isStarted());
        } finally {
            courier.close();
        }
    }

    @Test
    void shouldExportAndInjectAnnotatedServiceAcrossBootstraps() {
        YunqiCourierBootstrap provider = new YunqiCourierBootstrap(config(availablePort()));
        YunqiCourierBootstrap consumer = new YunqiCourierBootstrap(config(availablePort()));
        try {
            provider.exportAnnotated(new AnnotatedGreetingServiceImpl());
            provider.start();

            AnnotatedConsumer target = new AnnotatedConsumer();
            consumer.injectReferences(target);

            assertNotNull(target.greeting);
            assertEquals("hello, annotation", target.greeting.greet("annotation"));
        } finally {
            consumer.close();
            provider.close();
        }
    }

    @Test
    void shouldRejectServiceKeyDelimiterInGroupAndVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> ServiceExportConfig.of(GreetingService.class, new GreetingServiceImpl()).group("bad:group"));
        assertThrows(IllegalArgumentException.class,
                () -> ServiceReferenceConfig.of(GreetingService.class).version("bad:version"));
    }

    @Test
    void shouldRejectWildcardAddressWithoutAdvertisedEndpoint() {
        CourierConfig config = config(availablePort());
        config.setHost("0.0.0.0");
        YunqiCourierBootstrap courier = new YunqiCourierBootstrap(config);
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> courier.export(ServiceExportConfig.of(GreetingService.class, new GreetingServiceImpl())));
        } finally {
            courier.close();
        }
    }

    @Test
    void shouldUseWeightedLoadBalancingAndSkipUnhealthyProvider() {
        YunqiCourierBootstrap first = new YunqiCourierBootstrap(config(availablePort()));
        YunqiCourierBootstrap second = new YunqiCourierBootstrap(config(availablePort()));
        CourierConfig consumerConfig = config(availablePort());
        consumerConfig.setLoadBalancing("weighted");
        YunqiCourierBootstrap consumer = new YunqiCourierBootstrap(consumerConfig);
        try {
            first.export(ServiceExportConfig.of(WeightedGreetingService.class,
                    new WeightedGreetingServiceImpl("first")).weight(2).healthy(true));
            second.export(ServiceExportConfig.of(WeightedGreetingService.class,
                    new WeightedGreetingServiceImpl("second")).weight(1).healthy(false));
            first.start();
            second.start();

            WeightedGreetingService service = consumer.refer(ServiceReferenceConfig.of(WeightedGreetingService.class));
            for (int i = 0; i < 10; i++) {
                assertEquals("first", service.which());
            }
        } finally {
            consumer.close();
            second.close();
            first.close();
        }
    }

    @Test
    void shouldHonorConfiguredProviderWeightsAcrossNetty() {
        YunqiCourierBootstrap first = new YunqiCourierBootstrap(config(availablePort()));
        YunqiCourierBootstrap second = new YunqiCourierBootstrap(config(availablePort()));
        CourierConfig consumerConfig = config(availablePort());
        consumerConfig.setLoadBalancing("weighted");
        YunqiCourierBootstrap consumer = new YunqiCourierBootstrap(consumerConfig);
        try {
            first.export(ServiceExportConfig.of(WeightedGreetingService.class,
                    new WeightedGreetingServiceImpl("first")).weight(2));
            second.export(ServiceExportConfig.of(WeightedGreetingService.class,
                    new WeightedGreetingServiceImpl("second")).weight(1));
            first.start();
            second.start();

            WeightedGreetingService service = consumer.refer(ServiceReferenceConfig.of(WeightedGreetingService.class));
            int firstCount = 0;
            int secondCount = 0;
            for (int i = 0; i < 6; i++) {
                if ("first".equals(service.which())) {
                    firstCount++;
                } else {
                    secondCount++;
                }
            }
            assertEquals(4, firstCount);
            assertEquals(2, secondCount);
        } finally {
            consumer.close();
            second.close();
            first.close();
        }
    }

    private static CourierConfig config(int port) {
        CourierConfig config = new CourierConfig();
        config.setPort(port);
        config.setConnectTimeoutMillis(100);
        return config;
    }

    private static int availablePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new AssertionError("Could not allocate an ephemeral port", e);
        }
    }

    interface GreetingService {
        String greet(String name);
    }

    static class GreetingServiceImpl implements GreetingService {
        @Override
        public String greet(String name) {
            return "hello, " + name;
        }
    }

    @CourierProvider(interfaceClass = GreetingService.class)
    static final class AnnotatedGreetingServiceImpl implements GreetingService {

        @Override
        public String greet(String name) {
            return "hello, " + name;
        }
    }

    static final class AnnotatedConsumer {

        @CourierReference
        private GreetingService greeting;
    }

    interface WeightedGreetingService {
        String which();
    }

    static final class WeightedGreetingServiceImpl implements WeightedGreetingService {

        private final String name;

        private WeightedGreetingServiceImpl(String name) {
            this.name = name;
        }

        @Override
        public String which() {
            return name;
        }
    }
}
