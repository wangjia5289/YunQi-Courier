package yunqi.courier.api.bootstrap;

public class ServiceReferenceConfig<T> {

    private final Class<T> interfaceClass;

    private String group = "default";

    private String version = "1.0.0";

    private int timeoutMillis = -1;

    private int retries = -1;

    private boolean retryable;

    private boolean idempotent;

    private boolean circuitBreakerEnabled;

    private int circuitBreakerFailureThreshold = 5;

    private int circuitBreakerResetTimeoutMillis = 10000;

    private int rateLimitPerSecond;

    public ServiceReferenceConfig(Class<T> interfaceClass) {
        if (interfaceClass == null || !interfaceClass.isInterface()) {
            throw new IllegalArgumentException("interfaceClass must be an interface");
        }
        this.interfaceClass = interfaceClass;
    }

    public static <T> ServiceReferenceConfig<T> of(Class<T> interfaceClass) {
        return new ServiceReferenceConfig<>(interfaceClass);
    }

    public ServiceReferenceConfig<T> group(String group) {
        this.group = requireText(group, "group");
        return this;
    }

    public ServiceReferenceConfig<T> version(String version) {
        this.version = requireText(version, "version");
        return this;
    }

    public ServiceReferenceConfig<T> timeoutMillis(int timeoutMillis) {
        if (timeoutMillis == 0 || timeoutMillis < -1) {
            throw new IllegalArgumentException("timeoutMillis must be -1 or positive");
        }
        this.timeoutMillis = timeoutMillis;
        return this;
    }

    public ServiceReferenceConfig<T> retries(int retries) {
        if (retries < 0 || retries > 10) {
            throw new IllegalArgumentException("retries must be between 0 and 10");
        }
        this.retries = retries;
        return this;
    }

    /**
     * Enables retries for transport failures. Calls are rejected at invocation
     * time unless the operation is also declared idempotent.
     */
    public ServiceReferenceConfig<T> retryable(boolean retryable) {
        this.retryable = retryable;
        return this;
    }

    /**
     * Declares that the referenced operation is safe for transport retries.
     * Method-level {@code @CourierIdempotent} can be used when a single
     * interface contains both idempotent and non-idempotent operations.
     */
    public ServiceReferenceConfig<T> idempotent(boolean idempotent) {
        this.idempotent = idempotent;
        return this;
    }

    public ServiceReferenceConfig<T> circuitBreaker(boolean enabled) {
        this.circuitBreakerEnabled = enabled;
        return this;
    }

    public ServiceReferenceConfig<T> circuitBreakerFailureThreshold(int threshold) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("circuitBreakerFailureThreshold must be positive");
        }
        this.circuitBreakerFailureThreshold = threshold;
        return this;
    }

    public ServiceReferenceConfig<T> circuitBreakerResetTimeoutMillis(int timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("circuitBreakerResetTimeoutMillis must be positive");
        }
        this.circuitBreakerResetTimeoutMillis = timeoutMillis;
        return this;
    }

    public ServiceReferenceConfig<T> rateLimitPerSecond(int permits) {
        if (permits < 0) {
            throw new IllegalArgumentException("rateLimitPerSecond must not be negative");
        }
        this.rateLimitPerSecond = permits;
        return this;
    }

    public Class<T> getInterfaceClass() {
        return interfaceClass;
    }

    public String getGroup() {
        return group;
    }

    public String getVersion() {
        return version;
    }

    public int getTimeoutMillis() {
        return timeoutMillis;
    }

    public int getRetries() {
        return retries;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public boolean isIdempotent() {
        return idempotent;
    }

    public boolean isCircuitBreakerEnabled() {
        return circuitBreakerEnabled;
    }

    public int getCircuitBreakerFailureThreshold() {
        return circuitBreakerFailureThreshold;
    }

    public int getCircuitBreakerResetTimeoutMillis() {
        return circuitBreakerResetTimeoutMillis;
    }

    public int getRateLimitPerSecond() {
        return rateLimitPerSecond;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.indexOf(':') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " must not contain ':' or NUL");
        }
        if (value.length() > yunqi.courier.common.constant.CourierConstants.MAX_REQUEST_METADATA_STRING_LENGTH
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid or too long");
        }
        return value;
    }
}
