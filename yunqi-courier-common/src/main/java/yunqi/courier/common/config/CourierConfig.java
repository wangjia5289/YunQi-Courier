package yunqi.courier.common.config;

import yunqi.courier.common.constant.CourierConstants;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class CourierConfig {

    private String applicationName = "yunqi-courier-application";

    private String host = "127.0.0.1";

    private int port = CourierConstants.DEFAULT_PORT;

    /** Optional externally reachable address advertised to consumers. */
    private String advertisedHost;

    /** Optional externally reachable port; -1 means use the bind port. */
    private int advertisedPort = -1;

    private int timeoutMillis = CourierConstants.DEFAULT_TIMEOUT_MILLIS;

    private int connectTimeoutMillis = CourierConstants.DEFAULT_CONNECT_TIMEOUT_MILLIS;

    private int retries = 1;

    private int retryBackoffMillis = 10;

    private int maxFrameLength = CourierConstants.DEFAULT_MAX_FRAME_LENGTH;

    private int serverBusinessThreads = CourierConstants.DEFAULT_SERVER_BUSINESS_THREADS;

    private int serverQueueCapacity = CourierConstants.DEFAULT_SERVER_QUEUE_CAPACITY;

    private int maxConnections = CourierConstants.DEFAULT_MAX_CONNECTIONS;

    private int maxPendingRequests = CourierConstants.DEFAULT_MAX_PENDING_REQUESTS;

    private boolean clientAutoReconnect = true;

    private int clientReconnectBaseDelayMillis = 100;

    private int clientReconnectMaxDelayMillis = 5_000;

    private String registry = CourierConstants.DEFAULT_REGISTRY;

    private String network = CourierConstants.DEFAULT_NETWORK;

    private String serialization = CourierConstants.DEFAULT_SERIALIZATION;

    private String proxying = CourierConstants.DEFAULT_PROXYING;

    private String loadBalancing = CourierConstants.DEFAULT_LOAD_BALANCING;

    /** Optional payload compressor; {@code none} keeps the v1 payload unchanged. */
    private String compression = CourierConstants.DEFAULT_COMPRESSION;

    /** Optional payload encryptor; {@code none} keeps TLS as the transport boundary. */
    private String encryption = CourierConstants.DEFAULT_ENCRYPTION;

    /** Base64-encoded key material used by the configured payload encryptor. */
    private String encryptionKey;

    /** Shared secret used by the built-in request authentication policy. */
    private String authToken;

    private boolean requireAuthentication;

    /** Service-key to comma-separated method names. Empty means all methods. */
    private Map<String, String> authorizationRules = new HashMap<>();

    private int registryLeaseTtlMillis = 30_000;

    private int registryWatchReconnectMillis = 1_000;

    private String registryFilePath;

    /** ZooKeeper/Curator connect string, for example {@code 127.0.0.1:2181}. */
    private String registryAddress;

    /** ZooKeeper namespace used to isolate YunQi-Courier paths. */
    private String registryNamespace = "yunqi-courier";

    private int registryConnectTimeoutMillis = 5_000;

    private int registrySessionTimeoutMillis = 15_000;

    private int registryRetryBaseSleepMillis = 1_000;

    private int registryRetryMaxRetries = 5;

    /** Enables authenticated ZooKeeper access and per-node digest ACLs. */
    private boolean registrySecurityEnabled;

    private String registrySecurityUsername;

    private String registrySecurityPassword;

    /** Enables ZooKeeper's TLS client transport (independent from Netty TLS). */
    private boolean registryTlsEnabled;

    private String registryTlsKeyStorePath;

    private String registryTlsKeyStorePassword;

    private String registryTlsKeyStoreType = "PKCS12";

    private String registryTlsTrustStorePath;

    private String registryTlsTrustStorePassword;

    private String registryTlsTrustStoreType = "PKCS12";

    private boolean registryTlsHostnameVerificationEnabled = true;

    private boolean activeHealthChecks;

    private int healthCheckIntervalMillis = 5_000;

    /** Enables the optional JDK management server for liveness/readiness probes. */
    private boolean managementEnabled;

    private String managementHost = "127.0.0.1";

    private int managementPort = 8081;

    /** Enables TLS for the Netty transport. Plaintext remains the default. */
    private boolean tlsEnabled;

    /** Requires and presents client certificates in addition to server authentication. */
    private boolean tlsMutualAuth;

    private String tlsKeyStorePath;

    private String tlsKeyStorePassword;

    private String tlsKeyStoreType = "PKCS12";

    private String tlsTrustStorePath;

    private String tlsTrustStorePassword;

    private String tlsTrustStoreType = "PKCS12";

    private String[] tlsProtocols = {"TLSv1.3", "TLSv1.2"};

    private boolean tlsHostnameVerificationEnabled = true;

    public String getApplicationName() {
        return applicationName;
    }

    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getAdvertisedHost() {
        return advertisedHost;
    }

    public void setAdvertisedHost(String advertisedHost) {
        this.advertisedHost = advertisedHost;
    }

    public int getAdvertisedPort() {
        return advertisedPort;
    }

    public void setAdvertisedPort(int advertisedPort) {
        this.advertisedPort = advertisedPort;
    }

    public int getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public int getRetries() {
        return retries;
    }

    public void setRetries(int retries) {
        this.retries = retries;
    }

    public int getRetryBackoffMillis() {
        return retryBackoffMillis;
    }

    public void setRetryBackoffMillis(int retryBackoffMillis) {
        this.retryBackoffMillis = retryBackoffMillis;
    }

    public int getMaxFrameLength() {
        return maxFrameLength;
    }

    public void setMaxFrameLength(int maxFrameLength) {
        this.maxFrameLength = maxFrameLength;
    }

    public int getServerBusinessThreads() {
        return serverBusinessThreads;
    }

    public void setServerBusinessThreads(int serverBusinessThreads) {
        this.serverBusinessThreads = serverBusinessThreads;
    }

    public int getServerQueueCapacity() {
        return serverQueueCapacity;
    }

    public void setServerQueueCapacity(int serverQueueCapacity) {
        this.serverQueueCapacity = serverQueueCapacity;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public int getMaxPendingRequests() {
        return maxPendingRequests;
    }

    public void setMaxPendingRequests(int maxPendingRequests) {
        this.maxPendingRequests = maxPendingRequests;
    }

    public boolean isClientAutoReconnect() {
        return clientAutoReconnect;
    }

    public void setClientAutoReconnect(boolean clientAutoReconnect) {
        this.clientAutoReconnect = clientAutoReconnect;
    }

    public int getClientReconnectBaseDelayMillis() {
        return clientReconnectBaseDelayMillis;
    }

    public void setClientReconnectBaseDelayMillis(int clientReconnectBaseDelayMillis) {
        this.clientReconnectBaseDelayMillis = clientReconnectBaseDelayMillis;
    }

    public int getClientReconnectMaxDelayMillis() {
        return clientReconnectMaxDelayMillis;
    }

    public void setClientReconnectMaxDelayMillis(int clientReconnectMaxDelayMillis) {
        this.clientReconnectMaxDelayMillis = clientReconnectMaxDelayMillis;
    }

    public String getRegistry() {
        return registry;
    }

    public void setRegistry(String registry) {
        this.registry = registry;
    }

    public String getNetwork() {
        return network;
    }

    public void setNetwork(String network) {
        this.network = network;
    }

    public String getSerialization() {
        return serialization;
    }

    public void setSerialization(String serialization) {
        this.serialization = serialization;
    }

    public String getProxying() {
        return proxying;
    }

    public void setProxying(String proxying) {
        this.proxying = proxying;
    }

    public String getLoadBalancing() {
        return loadBalancing;
    }

    public void setLoadBalancing(String loadBalancing) {
        this.loadBalancing = loadBalancing;
    }

    public String getCompression() {
        return compression;
    }

    public void setCompression(String compression) {
        this.compression = compression;
    }

    public String getEncryption() {
        return encryption;
    }

    public void setEncryption(String encryption) {
        this.encryption = encryption;
    }

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    public boolean isRequireAuthentication() {
        return requireAuthentication;
    }

    public void setRequireAuthentication(boolean requireAuthentication) {
        this.requireAuthentication = requireAuthentication;
    }

    public Map<String, String> getAuthorizationRules() {
        return Collections.unmodifiableMap(new HashMap<>(authorizationRules));
    }

    public void setAuthorizationRules(Map<String, String> authorizationRules) {
        this.authorizationRules = authorizationRules == null ? new HashMap<>() : new HashMap<>(authorizationRules);
    }

    public CourierConfig authorizationRule(String serviceKey, String methods) {
        if (serviceKey == null || serviceKey.isBlank()) {
            throw new IllegalArgumentException("serviceKey must not be blank");
        }
        authorizationRules.put(serviceKey, methods == null ? "" : methods);
        return this;
    }

    public int getRegistryLeaseTtlMillis() {
        return registryLeaseTtlMillis;
    }

    public void setRegistryLeaseTtlMillis(int registryLeaseTtlMillis) {
        this.registryLeaseTtlMillis = registryLeaseTtlMillis;
    }

    public int getRegistryWatchReconnectMillis() {
        return registryWatchReconnectMillis;
    }

    public void setRegistryWatchReconnectMillis(int registryWatchReconnectMillis) {
        this.registryWatchReconnectMillis = registryWatchReconnectMillis;
    }

    public String getRegistryFilePath() {
        return registryFilePath;
    }

    public void setRegistryFilePath(String registryFilePath) {
        this.registryFilePath = registryFilePath;
    }

    public String getRegistryAddress() {
        return registryAddress;
    }

    public void setRegistryAddress(String registryAddress) {
        this.registryAddress = registryAddress;
    }

    /** Alias retained for callers that name the backend explicitly. */
    public String getZookeeperAddress() {
        return registryAddress;
    }

    public void setZookeeperAddress(String zookeeperAddress) {
        this.registryAddress = zookeeperAddress;
    }

    /** Alias using the conventional ZooKeeper capitalization. */
    public String getZooKeeperAddress() {
        return registryAddress;
    }

    public void setZooKeeperAddress(String zooKeeperAddress) {
        this.registryAddress = zooKeeperAddress;
    }

    public String getRegistryZookeeperAddress() {
        return registryAddress;
    }

    public void setRegistryZookeeperAddress(String registryZookeeperAddress) {
        this.registryAddress = registryZookeeperAddress;
    }

    public String getZookeeperConnectString() {
        return registryAddress;
    }

    public void setZookeeperConnectString(String zookeeperConnectString) {
        this.registryAddress = zookeeperConnectString;
    }

    public String getRegistryNamespace() {
        return registryNamespace;
    }

    public void setRegistryNamespace(String registryNamespace) {
        this.registryNamespace = registryNamespace;
    }

    public int getRegistryConnectTimeoutMillis() {
        return registryConnectTimeoutMillis;
    }

    public void setRegistryConnectTimeoutMillis(int registryConnectTimeoutMillis) {
        this.registryConnectTimeoutMillis = registryConnectTimeoutMillis;
    }

    public int getRegistrySessionTimeoutMillis() {
        return registrySessionTimeoutMillis;
    }

    public void setRegistrySessionTimeoutMillis(int registrySessionTimeoutMillis) {
        this.registrySessionTimeoutMillis = registrySessionTimeoutMillis;
    }

    public int getRegistryRetryBaseSleepMillis() {
        return registryRetryBaseSleepMillis;
    }

    public void setRegistryRetryBaseSleepMillis(int registryRetryBaseSleepMillis) {
        this.registryRetryBaseSleepMillis = registryRetryBaseSleepMillis;
    }

    public int getRegistryRetryMaxRetries() {
        return registryRetryMaxRetries;
    }

    public void setRegistryRetryMaxRetries(int registryRetryMaxRetries) {
        this.registryRetryMaxRetries = registryRetryMaxRetries;
    }

    public boolean isRegistrySecurityEnabled() {
        return registrySecurityEnabled;
    }

    public void setRegistrySecurityEnabled(boolean registrySecurityEnabled) {
        this.registrySecurityEnabled = registrySecurityEnabled;
    }

    public String getRegistrySecurityUsername() {
        return registrySecurityUsername;
    }

    public void setRegistrySecurityUsername(String registrySecurityUsername) {
        this.registrySecurityUsername = registrySecurityUsername;
    }

    public String getRegistrySecurityPassword() {
        return registrySecurityPassword;
    }

    public void setRegistrySecurityPassword(String registrySecurityPassword) {
        this.registrySecurityPassword = registrySecurityPassword;
    }

    public boolean isRegistryTlsEnabled() {
        return registryTlsEnabled;
    }

    public void setRegistryTlsEnabled(boolean registryTlsEnabled) {
        this.registryTlsEnabled = registryTlsEnabled;
    }

    public String getRegistryTlsKeyStorePath() {
        return registryTlsKeyStorePath;
    }

    public void setRegistryTlsKeyStorePath(String registryTlsKeyStorePath) {
        this.registryTlsKeyStorePath = registryTlsKeyStorePath;
    }

    public String getRegistryTlsKeyStorePassword() {
        return registryTlsKeyStorePassword;
    }

    public void setRegistryTlsKeyStorePassword(String registryTlsKeyStorePassword) {
        this.registryTlsKeyStorePassword = registryTlsKeyStorePassword;
    }

    public String getRegistryTlsKeyStoreType() {
        return registryTlsKeyStoreType;
    }

    public void setRegistryTlsKeyStoreType(String registryTlsKeyStoreType) {
        this.registryTlsKeyStoreType = registryTlsKeyStoreType;
    }

    public String getRegistryTlsTrustStorePath() {
        return registryTlsTrustStorePath;
    }

    public void setRegistryTlsTrustStorePath(String registryTlsTrustStorePath) {
        this.registryTlsTrustStorePath = registryTlsTrustStorePath;
    }

    public String getRegistryTlsTrustStorePassword() {
        return registryTlsTrustStorePassword;
    }

    public void setRegistryTlsTrustStorePassword(String registryTlsTrustStorePassword) {
        this.registryTlsTrustStorePassword = registryTlsTrustStorePassword;
    }

    public String getRegistryTlsTrustStoreType() {
        return registryTlsTrustStoreType;
    }

    public void setRegistryTlsTrustStoreType(String registryTlsTrustStoreType) {
        this.registryTlsTrustStoreType = registryTlsTrustStoreType;
    }

    public boolean isRegistryTlsHostnameVerificationEnabled() {
        return registryTlsHostnameVerificationEnabled;
    }

    public void setRegistryTlsHostnameVerificationEnabled(boolean registryTlsHostnameVerificationEnabled) {
        this.registryTlsHostnameVerificationEnabled = registryTlsHostnameVerificationEnabled;
    }

    public boolean isActiveHealthChecks() {
        return activeHealthChecks;
    }

    public void setActiveHealthChecks(boolean activeHealthChecks) {
        this.activeHealthChecks = activeHealthChecks;
    }

    public int getHealthCheckIntervalMillis() {
        return healthCheckIntervalMillis;
    }

    public void setHealthCheckIntervalMillis(int healthCheckIntervalMillis) {
        this.healthCheckIntervalMillis = healthCheckIntervalMillis;
    }

    public boolean isManagementEnabled() {
        return managementEnabled;
    }

    public void setManagementEnabled(boolean managementEnabled) {
        this.managementEnabled = managementEnabled;
    }

    public String getManagementHost() {
        return managementHost;
    }

    public void setManagementHost(String managementHost) {
        this.managementHost = managementHost;
    }

    public int getManagementPort() {
        return managementPort;
    }

    public void setManagementPort(int managementPort) {
        this.managementPort = managementPort;
    }

    public boolean isTlsEnabled() {
        return tlsEnabled;
    }

    public void setTlsEnabled(boolean tlsEnabled) {
        this.tlsEnabled = tlsEnabled;
    }

    public boolean isTlsMutualAuth() {
        return tlsMutualAuth;
    }

    public void setTlsMutualAuth(boolean tlsMutualAuth) {
        this.tlsMutualAuth = tlsMutualAuth;
    }

    public String getTlsKeyStorePath() {
        return tlsKeyStorePath;
    }

    public void setTlsKeyStorePath(String tlsKeyStorePath) {
        this.tlsKeyStorePath = tlsKeyStorePath;
    }

    public String getTlsKeyStorePassword() {
        return tlsKeyStorePassword;
    }

    public void setTlsKeyStorePassword(String tlsKeyStorePassword) {
        this.tlsKeyStorePassword = tlsKeyStorePassword;
    }

    public String getTlsKeyStoreType() {
        return tlsKeyStoreType;
    }

    public void setTlsKeyStoreType(String tlsKeyStoreType) {
        this.tlsKeyStoreType = tlsKeyStoreType;
    }

    public String getTlsTrustStorePath() {
        return tlsTrustStorePath;
    }

    public void setTlsTrustStorePath(String tlsTrustStorePath) {
        this.tlsTrustStorePath = tlsTrustStorePath;
    }

    public String getTlsTrustStorePassword() {
        return tlsTrustStorePassword;
    }

    public void setTlsTrustStorePassword(String tlsTrustStorePassword) {
        this.tlsTrustStorePassword = tlsTrustStorePassword;
    }

    public String getTlsTrustStoreType() {
        return tlsTrustStoreType;
    }

    public void setTlsTrustStoreType(String tlsTrustStoreType) {
        this.tlsTrustStoreType = tlsTrustStoreType;
    }

    public String[] getTlsProtocols() {
        return tlsProtocols.clone();
    }

    public void setTlsProtocols(String... tlsProtocols) {
        this.tlsProtocols = tlsProtocols == null ? null : tlsProtocols.clone();
    }

    public boolean isTlsHostnameVerificationEnabled() {
        return tlsHostnameVerificationEnabled;
    }

    public void setTlsHostnameVerificationEnabled(boolean tlsHostnameVerificationEnabled) {
        this.tlsHostnameVerificationEnabled = tlsHostnameVerificationEnabled;
    }

    /** Alias for callers that use the shorter TLS hostname-verification name. */
    public boolean isTlsHostnameVerification() {
        return tlsHostnameVerificationEnabled;
    }

    public void setTlsHostnameVerification(boolean tlsHostnameVerificationEnabled) {
        this.tlsHostnameVerificationEnabled = tlsHostnameVerificationEnabled;
    }

    public void validate() {
        if (applicationName == null || applicationName.isBlank()) {
            throw new IllegalArgumentException("applicationName must not be blank");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (!host.equals(host.trim()) || host.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("host must not have surrounding whitespace or control characters");
        }
        if (port < CourierConstants.MIN_PORT || port > CourierConstants.MAX_PORT) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        if (advertisedHost != null && (advertisedHost.isBlank() || !advertisedHost.equals(advertisedHost.trim())
                || advertisedHost.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("advertisedHost must not be blank, padded or contain control characters");
        }
        if (advertisedPort != -1
                && (advertisedPort < CourierConstants.MIN_PORT || advertisedPort > CourierConstants.MAX_PORT)) {
            throw new IllegalArgumentException("advertisedPort must be -1 or between 1 and 65535");
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        if (connectTimeoutMillis <= 0) {
            throw new IllegalArgumentException("connectTimeoutMillis must be positive");
        }
        if (retries < 0 || retries > 10) {
            throw new IllegalArgumentException("retries must be between 0 and 10");
        }
        if (retryBackoffMillis < 0) {
            throw new IllegalArgumentException("retryBackoffMillis must not be negative");
        }
        if (maxFrameLength < 1024 || maxFrameLength > CourierConstants.MAX_MAX_FRAME_LENGTH) {
            throw new IllegalArgumentException("maxFrameLength must be between 1024 and "
                    + CourierConstants.MAX_MAX_FRAME_LENGTH);
        }
        if (serverBusinessThreads <= 0) {
            throw new IllegalArgumentException("serverBusinessThreads must be positive");
        }
        if (serverQueueCapacity <= 0) {
            throw new IllegalArgumentException("serverQueueCapacity must be positive");
        }
        if (maxConnections <= 0 || maxConnections > 1_000_000) {
            throw new IllegalArgumentException("maxConnections must be between 1 and 1000000");
        }
        if (maxPendingRequests <= 0 || maxPendingRequests > 1_000_000) {
            throw new IllegalArgumentException("maxPendingRequests must be between 1 and 1000000");
        }
        if (clientReconnectBaseDelayMillis <= 0) {
            throw new IllegalArgumentException("clientReconnectBaseDelayMillis must be positive");
        }
        if (clientReconnectMaxDelayMillis < clientReconnectBaseDelayMillis
                || clientReconnectMaxDelayMillis > 300_000) {
            throw new IllegalArgumentException("clientReconnectMaxDelayMillis must be >= base and <= 300000");
        }
        if (registryLeaseTtlMillis <= 0) {
            throw new IllegalArgumentException("registryLeaseTtlMillis must be positive");
        }
        if (registryWatchReconnectMillis < 0) {
            throw new IllegalArgumentException("registryWatchReconnectMillis must not be negative");
        }
        if (registryFilePath != null && registryFilePath.isBlank()) {
            throw new IllegalArgumentException("registryFilePath must not be blank");
        }
        if (registryAddress != null && registryAddress.isBlank()) {
            throw new IllegalArgumentException("registryAddress must not be blank");
        }
        if (registryNamespace == null || registryNamespace.isBlank()
                || !registryNamespace.equals(registryNamespace.trim())
                || registryNamespace.chars().anyMatch(Character::isISOControl)
                || registryNamespace.contains("//")
                || registryNamespace.contains("..")) {
            throw new IllegalArgumentException("registryNamespace must be a valid non-empty path segment");
        }
        if (registryConnectTimeoutMillis <= 0) {
            throw new IllegalArgumentException("registryConnectTimeoutMillis must be positive");
        }
        if (registrySessionTimeoutMillis <= 0) {
            throw new IllegalArgumentException("registrySessionTimeoutMillis must be positive");
        }
        if (registryRetryBaseSleepMillis < 0) {
            throw new IllegalArgumentException("registryRetryBaseSleepMillis must not be negative");
        }
        if (registryRetryMaxRetries < 0 || registryRetryMaxRetries > 100) {
            throw new IllegalArgumentException("registryRetryMaxRetries must be between 0 and 100");
        }
        if (registrySecurityEnabled) {
            requireText(registrySecurityUsername, "registrySecurityUsername");
            requireText(registrySecurityPassword, "registrySecurityPassword");
            if (!registryTlsEnabled) {
                throw new IllegalArgumentException("registryTlsEnabled is required when ZooKeeper security is enabled");
            }
        }
        validateStorePath(registryTlsKeyStorePath, "registryTlsKeyStorePath");
        validateStorePath(registryTlsTrustStorePath, "registryTlsTrustStorePath");
        requireName(registryTlsKeyStoreType, "registryTlsKeyStoreType");
        requireName(registryTlsTrustStoreType, "registryTlsTrustStoreType");
        if (registryTlsEnabled && (registryTlsTrustStorePath == null || registryTlsTrustStorePath.isBlank())) {
            throw new IllegalArgumentException("registryTlsTrustStorePath is required when registryTlsEnabled is true");
        }
        if (healthCheckIntervalMillis <= 0) {
            throw new IllegalArgumentException("healthCheckIntervalMillis must be positive");
        }
        requireText(managementHost, "managementHost");
        if (managementPort <= 0 || managementPort > 65_535) {
            throw new IllegalArgumentException("managementPort must be between 1 and 65535");
        }
        requireName(registry, "registry");
        requireName(network, "network");
        requireName(serialization, "serialization");
        requireName(proxying, "proxying");
        requireName(loadBalancing, "loadBalancing");
        requireName(compression, "compression");
        requireName(encryption, "encryption");
        if (!CourierConstants.DEFAULT_ENCRYPTION.equals(encryption)
                && (encryptionKey == null || encryptionKey.isBlank())) {
            throw new IllegalArgumentException("encryptionKey is required when encryption is enabled");
        }
        if (encryptionKey != null && encryptionKey.length() > CourierConstants.MAX_REQUEST_METADATA_STRING_LENGTH) {
            throw new IllegalArgumentException("encryptionKey exceeds "
                    + CourierConstants.MAX_REQUEST_METADATA_STRING_LENGTH + " characters");
        }
        if (authToken != null && authToken.length() > CourierConstants.MAX_REQUEST_METADATA_STRING_LENGTH) {
            throw new IllegalArgumentException("authToken exceeds "
                    + CourierConstants.MAX_REQUEST_METADATA_STRING_LENGTH + " characters");
        }
        if (requireAuthentication && (authToken == null || authToken.isBlank())) {
            throw new IllegalArgumentException("authToken is required when authentication is enabled");
        }
        if (authToken != null && !authToken.isBlank() && !tlsEnabled) {
            throw new IllegalArgumentException("TLS is required when authToken is configured");
        }
        if (tlsKeyStorePath != null && tlsKeyStorePath.isBlank()) {
            throw new IllegalArgumentException("tlsKeyStorePath must not be blank");
        }
        if (tlsTrustStorePath != null && tlsTrustStorePath.isBlank()) {
            throw new IllegalArgumentException("tlsTrustStorePath must not be blank");
        }
        requireName(tlsKeyStoreType, "tlsKeyStoreType");
        requireName(tlsTrustStoreType, "tlsTrustStoreType");
        if (tlsProtocols == null || tlsProtocols.length == 0) {
            throw new IllegalArgumentException("tlsProtocols must not be empty");
        }
        for (String protocol : tlsProtocols) {
            if (protocol == null || protocol.isBlank()) {
                throw new IllegalArgumentException("tlsProtocols must contain non-blank values");
            }
        }
        if (tlsMutualAuth && !tlsEnabled) {
            throw new IllegalArgumentException("tlsMutualAuth requires tlsEnabled");
        }
        if (tlsMutualAuth && (tlsTrustStorePath == null || tlsTrustStorePath.isBlank())) {
            throw new IllegalArgumentException("tlsTrustStorePath is required when tlsMutualAuth is enabled");
        }
    }

    public CourierConfig copy() {
        CourierConfig copy = new CourierConfig();
        copy.applicationName = applicationName;
        copy.host = host;
        copy.port = port;
        copy.advertisedHost = advertisedHost;
        copy.advertisedPort = advertisedPort;
        copy.timeoutMillis = timeoutMillis;
        copy.connectTimeoutMillis = connectTimeoutMillis;
        copy.retries = retries;
        copy.retryBackoffMillis = retryBackoffMillis;
        copy.maxFrameLength = maxFrameLength;
        copy.serverBusinessThreads = serverBusinessThreads;
        copy.serverQueueCapacity = serverQueueCapacity;
        copy.maxConnections = maxConnections;
        copy.maxPendingRequests = maxPendingRequests;
        copy.clientAutoReconnect = clientAutoReconnect;
        copy.clientReconnectBaseDelayMillis = clientReconnectBaseDelayMillis;
        copy.clientReconnectMaxDelayMillis = clientReconnectMaxDelayMillis;
        copy.registry = registry;
        copy.network = network;
        copy.serialization = serialization;
        copy.proxying = proxying;
        copy.loadBalancing = loadBalancing;
        copy.compression = compression;
        copy.encryption = encryption;
        copy.encryptionKey = encryptionKey;
        copy.authToken = authToken;
        copy.requireAuthentication = requireAuthentication;
        copy.authorizationRules = new HashMap<>(authorizationRules);
        copy.registryLeaseTtlMillis = registryLeaseTtlMillis;
        copy.registryWatchReconnectMillis = registryWatchReconnectMillis;
        copy.registryFilePath = registryFilePath;
        copy.registryAddress = registryAddress;
        copy.registryNamespace = registryNamespace;
        copy.registryConnectTimeoutMillis = registryConnectTimeoutMillis;
        copy.registrySessionTimeoutMillis = registrySessionTimeoutMillis;
        copy.registryRetryBaseSleepMillis = registryRetryBaseSleepMillis;
        copy.registryRetryMaxRetries = registryRetryMaxRetries;
        copy.registrySecurityEnabled = registrySecurityEnabled;
        copy.registrySecurityUsername = registrySecurityUsername;
        copy.registrySecurityPassword = registrySecurityPassword;
        copy.registryTlsEnabled = registryTlsEnabled;
        copy.registryTlsKeyStorePath = registryTlsKeyStorePath;
        copy.registryTlsKeyStorePassword = registryTlsKeyStorePassword;
        copy.registryTlsKeyStoreType = registryTlsKeyStoreType;
        copy.registryTlsTrustStorePath = registryTlsTrustStorePath;
        copy.registryTlsTrustStorePassword = registryTlsTrustStorePassword;
        copy.registryTlsTrustStoreType = registryTlsTrustStoreType;
        copy.registryTlsHostnameVerificationEnabled = registryTlsHostnameVerificationEnabled;
        copy.activeHealthChecks = activeHealthChecks;
        copy.healthCheckIntervalMillis = healthCheckIntervalMillis;
        copy.managementEnabled = managementEnabled;
        copy.managementHost = managementHost;
        copy.managementPort = managementPort;
        copy.tlsEnabled = tlsEnabled;
        copy.tlsMutualAuth = tlsMutualAuth;
        copy.tlsKeyStorePath = tlsKeyStorePath;
        copy.tlsKeyStorePassword = tlsKeyStorePassword;
        copy.tlsKeyStoreType = tlsKeyStoreType;
        copy.tlsTrustStorePath = tlsTrustStorePath;
        copy.tlsTrustStorePassword = tlsTrustStorePassword;
        copy.tlsTrustStoreType = tlsTrustStoreType;
        copy.tlsProtocols = tlsProtocols == null ? null : tlsProtocols.clone();
        copy.tlsHostnameVerificationEnabled = tlsHostnameVerificationEnabled;
        return copy;
    }

    private static void requireName(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl)
                || value.length() > CourierConstants.MAX_REQUEST_METADATA_STRING_LENGTH) {
            throw new IllegalArgumentException(field + " must be non-blank, bounded and free of control characters");
        }
    }

    private static void validateStorePath(String value, String field) {
        if (value != null && (value.isBlank() || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException(field + " must not be blank, padded or contain control characters");
        }
    }
}
