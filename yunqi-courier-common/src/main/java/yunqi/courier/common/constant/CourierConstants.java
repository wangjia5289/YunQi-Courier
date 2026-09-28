package yunqi.courier.common.constant;

public final class CourierConstants {

    public static final String DEFAULT_REGISTRY = "memory";

    public static final String DEFAULT_NETWORK = "netty";

    public static final String DEFAULT_SERIALIZATION = "json";

    public static final String DEFAULT_PROXYING = "jdk";

    public static final String DEFAULT_LOAD_BALANCING = "random";

    public static final String DEFAULT_COMPRESSION = "none";

    public static final String DEFAULT_ENCRYPTION = "none";

    public static final String SERVICE_ATTRIBUTE_WEIGHT = "weight";

    public static final String SERVICE_ATTRIBUTE_HEALTHY = "healthy";

    public static final String SERVICE_ATTRIBUTE_HEALTH = "health";

    public static final int DEFAULT_PORT = 20880;

    public static final int DEFAULT_TIMEOUT_MILLIS = 3000;

    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 1000;

    public static final int DEFAULT_MAX_FRAME_LENGTH = 16 * 1024 * 1024;

    public static final int MAX_MAX_FRAME_LENGTH = 64 * 1024 * 1024;

    public static final int DEFAULT_SERVER_BUSINESS_THREADS = 8;

    public static final int DEFAULT_SERVER_QUEUE_CAPACITY = 1000;

    public static final int DEFAULT_MAX_CONNECTIONS = 1_024;

    public static final int DEFAULT_MAX_PENDING_REQUESTS = 10_000;

    /** Bounds request metadata before it reaches reflection or the business executor. */
    public static final int MAX_REQUEST_PARAMETERS = 256;

    public static final int MAX_REQUEST_ATTACHMENTS = 64;

    public static final int MAX_REQUEST_METADATA_STRING_LENGTH = 4_096;

    public static final int MAX_ERROR_MESSAGE_LENGTH = 4_096;

    public static final int MIN_PORT = 1;

    public static final int MAX_PORT = 65535;

    public static final int PROTOCOL_MAGIC = 0x59514352;

    public static final byte PROTOCOL_VERSION = 1;

    private CourierConstants() {
    }
}
