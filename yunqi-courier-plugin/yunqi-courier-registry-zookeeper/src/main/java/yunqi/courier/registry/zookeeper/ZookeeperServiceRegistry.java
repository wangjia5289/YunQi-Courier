package yunqi.courier.registry.zookeeper;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.ACLProvider;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.server.auth.DigestAuthenticationProvider;
import org.apache.zookeeper.data.Stat;
import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.common.service.ServiceEndpoint;
import yunqi.courier.common.service.ServiceMetadata;
import yunqi.courier.kernel.spi.core.SPI;
import yunqi.courier.kernel.spi.registry.RegistryListener;
import yunqi.courier.kernel.spi.registry.ServiceRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Curator-backed distributed registry.
 *
 * <p>Providers are represented by ephemeral nodes below
 * {@code /services/<encoded-service-key>/providers}. The ZooKeeper session is
 * therefore the lease: a dead process is removed by ZooKeeper without a
 * cleanup request. CuratorCache keeps discovery local and its connection
 * listener recreates provider nodes after a session reconnect.</p>
 */
@SPI("zookeeper")
public class ZookeeperServiceRegistry implements ServiceRegistry {

    private static final System.Logger LOGGER = System.getLogger(ZookeeperServiceRegistry.class.getName());

    public static final String DEFAULT_NAMESPACE = "yunqi-courier";

    public static final String SERVICES_PATH = "/services";

    private static final int MAX_METADATA_BYTES = 1_048_576;

    private static final String ZK_SSL_HOSTNAME_VERIFICATION = "zookeeper.ssl.hostnameVerification";

    private static final String ZK_SSL_ENABLED_PROTOCOLS = "zookeeper.ssl.enabledProtocols";

    private static final String ZK_SSL_PROTOCOL = "zookeeper.ssl.protocol";

    private final ObjectMapper objectMapper = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxDocumentLength(MAX_METADATA_BYTES)
                    .maxStringLength(128 * 1024)
                    .maxNameLength(4_096)
                    .build())
            .build());

    private final Map<RegistrationKey, Registration> registrations = new ConcurrentHashMap<>();

    private final Map<String, List<ServiceMetadata>> discoveryCache = new ConcurrentHashMap<>();

    private final Map<String, WatchState> watches = new ConcurrentHashMap<>();

    private CuratorFramework client;

    private String namespace;

    private volatile boolean closed = true;

    private volatile List<ACL> registryAcls = immutableAclList(ZooDefs.Ids.OPEN_ACL_UNSAFE);

    private volatile String lastRecoveryFailure;

    private final AtomicLong recoveryFailureCount = new AtomicLong();

    @Override
    public synchronized void configure(CourierConfig config) {
        Objects.requireNonNull(config, "config");
        config.validate();
        close();

        String connectString = config.getRegistryAddress();
        if (connectString == null || connectString.isBlank()) {
            throw new IllegalArgumentException("registryAddress is required when registry=zookeeper");
        }
        connectString = normalizeConnectString(connectString);
        if (connectString.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("registryAddress must not contain control characters");
        }
        namespace = normalizeNamespace(config.getRegistryNamespace());
        lastRecoveryFailure = null;
        recoveryFailureCount.set(0);

        CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder()
                .connectString(connectString)
                .namespace(namespace.substring(1))
                .sessionTimeoutMs(config.getRegistrySessionTimeoutMillis())
                .connectionTimeoutMs(config.getRegistryConnectTimeoutMillis())
                .retryPolicy(new ExponentialBackoffRetry(
                        config.getRegistryRetryBaseSleepMillis(), config.getRegistryRetryMaxRetries()));
        registryAcls = config.isRegistrySecurityEnabled()
                ? DigestAclProvider.acls(config.getRegistrySecurityUsername(), config.getRegistrySecurityPassword())
                : immutableAclList(ZooDefs.Ids.OPEN_ACL_UNSAFE);
        if (config.isRegistrySecurityEnabled()) {
            String credentials = config.getRegistrySecurityUsername() + ":"
                    + config.getRegistrySecurityPassword();
            builder.authorization("digest", credentials.getBytes(StandardCharsets.UTF_8))
                    .aclProvider(new DigestAclProvider(config.getRegistrySecurityUsername(),
                            config.getRegistrySecurityPassword()));
        }
        if (config.isRegistryTlsEnabled()) {
            builder.zkClientConfig(tlsClientConfig(config));
        }
        CuratorFramework candidate = builder.build();
        candidate.getConnectionStateListenable().addListener((connectedClient, state) -> {
            if (state == org.apache.curator.framework.state.ConnectionState.RECONNECTED) {
                CompletableFuture.runAsync(() -> reRegisterAfterReconnect(connectedClient))
                        .exceptionally(failure -> {
                            recordRecoveryFailure("ZooKeeper reconnect recovery failed", failure);
                            return null;
                        });
            }
        });
        try {
            candidate.start();
            if (!candidate.blockUntilConnected(config.getRegistryConnectTimeoutMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Timed out connecting to ZooKeeper: " + connectString);
            }
            ensurePath(candidate, SERVICES_PATH);
            client = candidate;
            closed = false;
        } catch (InterruptedException e) {
            candidate.close();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while connecting to ZooKeeper", e);
        } catch (Exception e) {
            candidate.close();
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Failed to initialize ZooKeeper registry", e);
        }
    }

    @Override
    public synchronized void register(ServiceMetadata metadata) {
        Objects.requireNonNull(metadata, "service metadata");
        metadata.validate();
        CuratorFramework current = requireClient();
        RegistrationKey key = keyOf(metadata);
        Registration previous = registrations.get(key);
        String path = previous == null ? providerPath(metadata) + "/" + providerNodeName(metadata) : previous.path();
        try {
            ensurePath(current, providerPath(metadata));
            byte[] data = serialize(metadata);
            Stat existing = current.checkExists().forPath(path);
            if (existing == null) {
                try {
                    current.create().withMode(CreateMode.EPHEMERAL).withACL(registryAcls).forPath(path, data);
                } catch (KeeperException.NodeExistsException ignored) {
                    current.setData().forPath(path, data);
                }
            } else {
                current.setData().forPath(path, data);
            }
            registrations.put(key, new Registration(path, metadata.snapshot()));
            refreshCache(metadata.serviceKey());
        } catch (Exception e) {
            throw registryFailure("Register service failed, service=" + metadata.serviceKey(), e);
        }
    }

    @Override
    public synchronized void unregister(ServiceMetadata metadata) {
        if (metadata == null) {
            return;
        }
        Registration registration = registrations.remove(keyOf(metadata));
        if (registration == null || client == null) {
            discoveryCache.remove(metadata.serviceKey());
            return;
        }
        try {
            deleteIfPresent(client, registration.path());
            refreshCache(metadata.serviceKey());
        } catch (Exception e) {
            throw registryFailure("Unregister service failed, service=" + metadata.serviceKey(), e);
        }
    }

    @Override
    public synchronized List<ServiceMetadata> discover(String serviceKey) {
        if (serviceKey == null || serviceKey.isBlank()) {
            return List.of();
        }
        if (closed || client == null) {
            return List.of();
        }
        WatchState state = watches.get(serviceKey);
        if (state != null && state.initialized.get()) {
            return snapshot(discoveryCache.get(serviceKey));
        }
        CuratorFramework current = requireClient();
        try {
            List<ServiceMetadata> providers = fetchFromZooKeeper(current, serviceKey);
            discoveryCache.put(serviceKey, providers);
            return snapshot(providers);
        } catch (Exception e) {
            throw registryFailure("Discover service failed, service=" + serviceKey, e);
        }
    }

    /** ZooKeeper's ephemeral session is the lease; this also repairs a lost node. */
    @Override
    public synchronized void renew(ServiceMetadata metadata) {
        if (metadata == null || client == null) {
            return;
        }
        Registration registration = registrations.get(keyOf(metadata));
        if (registration == null) {
            register(metadata);
            return;
        }
        try {
            if (client.checkExists().forPath(registration.path()) == null) {
                register(registration.metadata());
            }
        } catch (Exception e) {
            throw registryFailure("Renew service lease failed, service=" + metadata.serviceKey(), e);
        }
    }

    @Override
    public synchronized AutoCloseable watch(String serviceKey, RegistryListener listener) {
        if (closed || client == null || serviceKey == null || serviceKey.isBlank() || listener == null) {
            return () -> {
            };
        }
        CuratorFramework current = requireClient();
        WatchState state = watches.get(serviceKey);
        if (state == null) {
            state = createWatch(current, serviceKey);
            watches.put(serviceKey, state);
        }
        state.listeners.addIfAbsent(listener);
        WatchHandle handle = new WatchHandle(state, listener);
        refreshState(state);
        return handle;
    }

    @Override
    public synchronized void updateHealth(ServiceMetadata metadata, boolean healthy) {
        if (metadata == null || client == null) {
            return;
        }
        RegistrationKey key = keyOf(metadata);
        Registration currentRegistration = registrations.get(key);
        if (currentRegistration == null) {
            return;
        }
        ServiceMetadata updated = currentRegistration.metadata().snapshot();
        Map<String, String> attributes = new HashMap<>(updated.getAttributes());
        attributes.put(yunqi.courier.common.constant.CourierConstants.SERVICE_ATTRIBUTE_HEALTHY,
                Boolean.toString(healthy));
        updated.setAttributes(attributes);
        try {
            client.setData().forPath(currentRegistration.path(), serialize(updated));
            registrations.put(key, new Registration(currentRegistration.path(), updated));
            refreshCache(metadata.serviceKey());
        } catch (KeeperException.NoNodeException e) {
            register(updated);
        } catch (Exception e) {
            throw registryFailure("Update service health failed, service=" + metadata.serviceKey(), e);
        }
    }

    @Override
    public synchronized void close() {
        CuratorFramework current = client;
        client = null;
        closed = true;
        if (current != null) {
            for (Registration registration : List.copyOf(registrations.values())) {
                try {
                    deleteIfPresent(current, registration.path());
                } catch (Exception ignored) {
                    // Session close still removes ephemeral nodes when explicit cleanup is unavailable.
                }
            }
        }
        registrations.clear();
        for (WatchState state : List.copyOf(watches.values())) {
            try {
                state.cache.close();
            } catch (RuntimeException ignored) {
                // Best effort during lifecycle cleanup.
            }
        }
        watches.clear();
        discoveryCache.clear();
        if (current != null) {
            current.close();
        }
    }

    /** Returns the namespace as an absolute ZooKeeper path for diagnostics. */
    public synchronized String getNamespace() {
        return namespace;
    }

    /** Returns the most recent reconnect recovery failure, if any. */
    public String getLastRecoveryFailure() {
        return lastRecoveryFailure;
    }

    /** Returns the number of provider recovery failures observed by this registry. */
    public long getRecoveryFailureCount() {
        return recoveryFailureCount.get();
    }

    private WatchState createWatch(CuratorFramework current, String serviceKey) {
        String path = providerPath(serviceKey);
        try {
            ensurePath(current, path);
            CuratorCache cache = CuratorCache.build(current, path);
            WatchState state = new WatchState(serviceKey, path, cache);
            cache.listenable().addListener((type, oldData, newData) -> refreshState(state));
            cache.start();
            return state;
        } catch (Exception e) {
            throw registryFailure("Create ZooKeeper watch failed, service=" + serviceKey, e);
        }
    }

    private void refreshState(WatchState state) {
        if (closed || watches.get(state.serviceKey) != state) {
            return;
        }
        List<ServiceMetadata> providers = new ArrayList<>();
        boolean refreshed = true;
        try {
            state.cache.stream()
                    .filter(child -> isDirectChild(state.path, child.getPath()))
                    .map(ChildData::getData)
                    .map(this::deserialize)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(ServiceMetadata::serviceKey)
                            .thenComparing(metadata -> metadata.getEndpoint().address()))
                    .forEach(providers::add);
        } catch (RuntimeException ignored) {
            // A transient cache rebuild will deliver another event after reconnect.
            refreshed = false;
        }
        if (!refreshed || closed || watches.get(state.serviceKey) != state) {
            return;
        }
        List<ServiceMetadata> immutable = List.copyOf(providers);
        discoveryCache.put(state.serviceKey, immutable);
        state.initialized.set(true);
        for (RegistryListener listener : state.listeners) {
            try {
                listener.onChange(state.serviceKey, snapshot(immutable));
            } catch (RuntimeException ignored) {
                // Consumer callbacks are isolated from registry notifications.
            }
        }
    }

    private void refreshCache(String serviceKey) {
        WatchState state = watches.get(serviceKey);
        if (state != null) {
            refreshState(state);
        } else if (client != null) {
            try {
                discoveryCache.put(serviceKey, fetchFromZooKeeper(client, serviceKey));
            } catch (Exception ignored) {
                // The next explicit discover will report the registry failure.
            }
        }
    }

    private void reRegisterAfterReconnect(CuratorFramework reconnectingClient) {
        synchronized (this) {
            if (closed || client != reconnectingClient) {
                return;
            }
            boolean recovered = true;
            for (Registration registration : List.copyOf(registrations.values())) {
                try {
                    ensurePath(reconnectingClient, providerPath(registration.metadata()));
                    byte[] data = serialize(registration.metadata());
                    if (reconnectingClient.checkExists().forPath(registration.path()) == null) {
                        reconnectingClient.create().withMode(CreateMode.EPHEMERAL).withACL(registryAcls)
                                .forPath(registration.path(), data);
                    } else {
                        reconnectingClient.setData().forPath(registration.path(), data);
                    }
                } catch (KeeperException.NodeExistsException ignored) {
                    // Another retry or a concurrent renew already recreated this node.
                } catch (Exception ignored) {
                    recovered = false;
                    recordRecoveryFailure("Provider re-registration failed, path=" + registration.path(), ignored);
                }
            }
            for (WatchState state : watches.values()) {
                state.initialized.set(false);
                refreshState(state);
            }
            if (recovered) {
                lastRecoveryFailure = null;
            }
        }
    }

    private List<ServiceMetadata> fetchFromZooKeeper(CuratorFramework current, String serviceKey) throws Exception {
        String path = providerPath(serviceKey);
        if (current.checkExists().forPath(path) == null) {
            return List.of();
        }
        List<ServiceMetadata> providers = new ArrayList<>();
        for (String child : current.getChildren().forPath(path).stream().sorted().toList()) {
            try {
                ServiceMetadata metadata = deserialize(current.getData().forPath(path + "/" + child));
                if (metadata != null) {
                    providers.add(metadata);
                }
            } catch (KeeperException.NoNodeException ignored) {
                // A provider can disappear between getChildren and getData.
            }
        }
        return List.copyOf(providers);
    }

    private byte[] serialize(ServiceMetadata metadata) {
        try {
            Map<String, Object> endpoint = new LinkedHashMap<>();
            endpoint.put("host", metadata.getEndpoint().getHost());
            endpoint.put("port", metadata.getEndpoint().getPort());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("serviceName", metadata.getServiceName());
            payload.put("group", metadata.getGroup());
            payload.put("version", metadata.getVersion());
            payload.put("endpoint", endpoint);
            payload.put("attributes", metadata.getAttributes());
            byte[] bytes = objectMapper.writeValueAsBytes(payload);
            if (bytes.length > MAX_METADATA_BYTES) {
                throw new IllegalArgumentException("Service metadata is too large for ZooKeeper");
            }
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Serialize service metadata failed", e);
        }
    }

    private ServiceMetadata deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_METADATA_BYTES) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(bytes);
            JsonNode endpoint = required(root, "endpoint");
            ServiceMetadata metadata = new ServiceMetadata();
            metadata.setServiceName(required(root, "serviceName").asText());
            metadata.setGroup(required(root, "group").asText());
            metadata.setVersion(required(root, "version").asText());
            metadata.setEndpoint(new ServiceEndpoint(required(endpoint, "host").asText(),
                    required(endpoint, "port").asInt()));
            JsonNode attributes = root.get("attributes");
            Map<String, String> parsedAttributes = new HashMap<>();
            if (attributes != null && attributes.isObject()) {
                attributes.fields().forEachRemaining(entry -> {
                    if (entry.getValue().isTextual()) {
                        parsedAttributes.put(entry.getKey(), entry.getValue().asText());
                    }
                });
            }
            metadata.setAttributes(parsedAttributes);
            metadata.validate();
            return metadata;
        } catch (RuntimeException | IOException e) {
            return null;
        }
    }

    private JsonNode required(JsonNode object, String field) {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) {
            throw new IllegalArgumentException("Missing service metadata field: " + field);
        }
        return value;
    }

    private CuratorFramework requireClient() {
        if (closed || client == null) {
            throw new IllegalStateException("ZooKeeper registry is not configured or is closed");
        }
        return client;
    }

    private String providerPath(ServiceMetadata metadata) {
        return providerPath(metadata.serviceKey());
    }

    private String providerPath(String serviceKey) {
        return SERVICES_PATH + "/" + encode(serviceKey) + "/providers";
    }

    private String providerNodeName(ServiceMetadata metadata) {
        return encode(metadata.getEndpoint().address()) + "-" + UUID.randomUUID();
    }

    private RegistrationKey keyOf(ServiceMetadata metadata) {
        metadata.validate();
        return new RegistrationKey(metadata.serviceKey(), metadata.getEndpoint().address());
    }

    private static boolean isDirectChild(String parent, String child) {
        return child != null && child.startsWith(parent + "/") && child.indexOf('/', parent.length() + 1) < 0;
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String normalizeNamespace(String value) {
        String namespace = value == null || value.isBlank() ? DEFAULT_NAMESPACE : value.trim();
        if (!namespace.startsWith("/")) {
            namespace = "/" + namespace;
        }
        while (namespace.endsWith("/") && namespace.length() > 1) {
            namespace = namespace.substring(0, namespace.length() - 1);
        }
        if (namespace.contains("//") || namespace.contains("..")
                || namespace.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("registryNamespace must be a valid ZooKeeper path");
        }
        return namespace;
    }

    private static String normalizeConnectString(String value) {
        String connectString = value.trim();
        if (connectString.regionMatches(true, 0, "zookeeper://", 0, "zookeeper://".length())) {
            connectString = connectString.substring("zookeeper://".length());
        }
        if (connectString.isBlank() || connectString.contains("://")
                || connectString.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("registryAddress must be a ZooKeeper host:port connect string");
        }
        return connectString;
    }

    private static List<ACL> immutableAclList(List<ACL> acls) {
        return Collections.unmodifiableList(new ArrayList<>(acls));
    }

    private void ensurePath(CuratorFramework current, String path) throws Exception {
        try {
            current.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT)
                    .withACL(registryAcls).forPath(path, new byte[0]);
        } catch (KeeperException.NodeExistsException ignored) {
            if (!registryAcls.equals(ZooDefs.Ids.OPEN_ACL_UNSAFE)) {
                current.setACL().withACL(registryAcls).forPath(path);
            }
        }
    }

    static ZKClientConfig tlsClientConfig(CourierConfig config) {
        ZKClientConfig clientConfig = new ZKClientConfig();
        clientConfig.setProperty(ZKClientConfig.SECURE_CLIENT, "true");
        clientConfig.setProperty(ZKClientConfig.ZOOKEEPER_CLIENT_CNXN_SOCKET,
                "org.apache.zookeeper.ClientCnxnSocketNetty");
        clientConfig.setProperty(ZK_SSL_HOSTNAME_VERIFICATION,
                Boolean.toString(config.isRegistryTlsHostnameVerificationEnabled()));
        clientConfig.setProperty(ZK_SSL_ENABLED_PROTOCOLS, "TLSv1.3,TLSv1.2");
        clientConfig.setProperty(ZK_SSL_PROTOCOL, "TLSv1.3");
        clientConfig.setProperty("zookeeper.ssl.trustStore.location", config.getRegistryTlsTrustStorePath());
        clientConfig.setProperty("zookeeper.ssl.trustStore.password", config.getRegistryTlsTrustStorePassword());
        clientConfig.setProperty("zookeeper.ssl.trustStore.type", config.getRegistryTlsTrustStoreType());
        if (config.getRegistryTlsKeyStorePath() != null && !config.getRegistryTlsKeyStorePath().isBlank()) {
            clientConfig.setProperty("zookeeper.ssl.keyStore.location", config.getRegistryTlsKeyStorePath());
            clientConfig.setProperty("zookeeper.ssl.keyStore.password", config.getRegistryTlsKeyStorePassword());
            clientConfig.setProperty("zookeeper.ssl.keyStore.type", config.getRegistryTlsKeyStoreType());
        }
        return clientConfig;
    }

    private void recordRecoveryFailure(String message, Throwable failure) {
        Throwable cause = failure == null ? new IllegalStateException(message) : failure;
        lastRecoveryFailure = cause.getMessage() == null ? cause.getClass().getName() : cause.getMessage();
        recoveryFailureCount.incrementAndGet();
        LOGGER.log(System.Logger.Level.WARNING, message, cause);
    }

    private static void deleteIfPresent(CuratorFramework current, String path) throws Exception {
        try {
            current.delete().guaranteed().forPath(path);
        } catch (KeeperException.NoNodeException ignored) {
            // Idempotent unregister.
        }
    }

    private static List<ServiceMetadata> snapshot(List<ServiceMetadata> providers) {
        if (providers == null || providers.isEmpty()) {
            return List.of();
        }
        return providers.stream().map(ServiceMetadata::snapshot).toList();
    }

    private static IllegalStateException registryFailure(String message, Exception cause) {
        return new IllegalStateException(message, cause);
    }

    private static final class DigestAclProvider implements ACLProvider {
        private final List<ACL> acls;

        private DigestAclProvider(String username, String password) {
            this.acls = aclList(username, password);
        }

        private static List<ACL> acls(String username, String password) {
            return new DigestAclProvider(username, password).acls;
        }

        private static List<ACL> aclList(String username, String password) {
            try {
                String digest = DigestAuthenticationProvider.generateDigest(username + ":" + password);
                return Collections.singletonList(new ACL(ZooDefs.Perms.ALL, new Id("digest", digest)));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException("ZooKeeper digest authentication is unavailable", e);
            }
        }

        @Override
        public List<ACL> getDefaultAcl() {
            return acls;
        }

        @Override
        public List<ACL> getAclForPath(String path) {
            return acls;
        }
    }

    private record RegistrationKey(String serviceKey, String endpoint) {
    }

    private record Registration(String path, ServiceMetadata metadata) {
    }

    private static final class WatchState {
        private final String serviceKey;
        private final String path;
        private final CuratorCache cache;
        private final CopyOnWriteArrayList<RegistryListener> listeners = new CopyOnWriteArrayList<>();
        private final AtomicBoolean initialized = new AtomicBoolean();

        private WatchState(String serviceKey, String path, CuratorCache cache) {
            this.serviceKey = serviceKey;
            this.path = path;
            this.cache = cache;
        }
    }

    private final class WatchHandle implements AutoCloseable {
        private final WatchState state;
        private final RegistryListener listener;
        private final AtomicBoolean closed = new AtomicBoolean();

        private WatchHandle(WatchState state, RegistryListener listener) {
            this.state = state;
            this.listener = listener;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            synchronized (ZookeeperServiceRegistry.this) {
                state.listeners.remove(listener);
                if (state.listeners.isEmpty() && watches.remove(state.serviceKey, state)) {
                    state.cache.close();
                    discoveryCache.remove(state.serviceKey);
                }
            }
        }
    }
}
