import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.common.service.ServiceEndpoint;
import yunqi.courier.common.service.ServiceMetadata;
import yunqi.courier.registry.zookeeper.ZookeeperServiceRegistry;

import java.time.Duration;
import java.util.Map;

/** Cross-JVM ZooKeeper TLS/digest smoke client used by ops/zookeeper/run-integration.sh. */
public final class DockerRegistrySmoke {

    private static final String NAMESPACE = "docker-integration";
    private static final String SERVICE = "docker.integration.Echo";

    private DockerRegistrySmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("usage: <provider|consumer|unauthorized> <connect> <tlsDir> [timeout]");
        }
        String mode = args[0];
        String connect = args[1];
        String tlsDir = args[2];
        Duration timeout = Duration.ofSeconds(args.length >= 4 ? Long.parseLong(args[3]) : 20);
        if ("unauthorized".equals(mode)) {
            verifyUnauthorized(connect, tlsDir);
            return;
        }
        if ("provider".equals(mode)) {
            runProvider(connect, tlsDir);
            return;
        }
        if ("consumer".equals(mode)) {
            verifyConsumer(connect, tlsDir, timeout);
            return;
        }
        throw new IllegalArgumentException("unknown mode: " + mode);
    }

    private static void runProvider(String connect, String tlsDir) throws Exception {
        ZookeeperServiceRegistry registry = new ZookeeperServiceRegistry();
        try {
            registry.configure(config(connect, tlsDir, 6_000, "courier-secret"));
            ServiceMetadata metadata = metadata();
            registry.register(metadata);
            System.out.println("PROVIDER_READY");
            System.out.flush();
            Thread.currentThread().join();
        } finally {
            registry.close();
        }
    }

    private static void verifyConsumer(String connect, String tlsDir, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        ZookeeperServiceRegistry registry = new ZookeeperServiceRegistry();
        try {
            registry.configure(config(connect, tlsDir, 6_000, "courier-secret"));
            while (System.nanoTime() < deadline) {
                if (registry.discover(metadata().serviceKey()).size() == 1) {
                    System.out.println("DISCOVERED");
                    return;
                }
                Thread.sleep(100);
            }
        } finally {
            registry.close();
        }
        throw new IllegalStateException("provider was not discovered before timeout");
    }

    private static void verifyUnauthorized(String connect, String tlsDir) {
        ZookeeperServiceRegistry registry = new ZookeeperServiceRegistry();
        try {
            registry.configure(config(connect, tlsDir, 6_000, "wrong-secret"));
        } catch (RuntimeException expected) {
            System.out.println("UNAUTHORIZED_REJECTED");
            return;
        } finally {
            registry.close();
        }
        throw new IllegalStateException("unauthorized ZooKeeper client connected to protected namespace");
    }

    private static CourierConfig config(String connect, String tlsDir, int sessionTimeout, String password) {
        CourierConfig config = new CourierConfig();
        config.setRegistry("zookeeper");
        config.setRegistryAddress(connect);
        config.setRegistryNamespace(NAMESPACE);
        config.setRegistryConnectTimeoutMillis(10_000);
        config.setRegistrySessionTimeoutMillis(sessionTimeout);
        config.setRegistryRetryBaseSleepMillis(250);
        config.setRegistryRetryMaxRetries(100);
        config.setRegistrySecurityEnabled(true);
        config.setRegistrySecurityUsername("courier");
        config.setRegistrySecurityPassword(password);
        config.setRegistryTlsEnabled(true);
        config.setRegistryTlsHostnameVerificationEnabled(true);
        config.setRegistryTlsTrustStorePath(tlsDir + "/client-trust.p12");
        config.setRegistryTlsTrustStorePassword("changeit");
        config.setRegistryTlsKeyStorePath(tlsDir + "/client.p12");
        config.setRegistryTlsKeyStorePassword("changeit");
        return config;
    }

    private static ServiceMetadata metadata() {
        ServiceMetadata metadata = new ServiceMetadata();
        metadata.setServiceName(SERVICE);
        metadata.setGroup("default");
        metadata.setVersion("1.0.0");
        metadata.setEndpoint(new ServiceEndpoint("127.0.0.1", 20_880));
        metadata.setAttributes(Map.of("weight", "1"));
        return metadata;
    }
}
