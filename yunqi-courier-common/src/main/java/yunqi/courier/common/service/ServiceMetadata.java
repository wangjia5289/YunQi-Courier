package yunqi.courier.common.service;

import yunqi.courier.common.constant.CourierConstants;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;

public class ServiceMetadata implements Serializable {

    private String serviceName;

    private String group = "default";

    private String version = "1.0.0";

    private ServiceEndpoint endpoint;

    private transient Class<?> serviceInterface;

    private Map<String, String> attributes = new HashMap<>();

    public String serviceKey() {
        return serviceName + ":" + group + ":" + version;
    }

    /**
     * Validates metadata at registry/export boundaries. Jackson field
     * deserialization can bypass setters, so callers must not rely on setter
     * validation alone.
     */
    public void validate() {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName must not be blank");
        }
        if (serviceName.length() > CourierConstants.MAX_REQUEST_METADATA_STRING_LENGTH
                || serviceName.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("serviceName is invalid or too long");
        }
        requireServiceKeyPart(group, "group");
        requireServiceKeyPart(version, "version");
        if (endpoint == null || !endpoint.isValid()) {
            throw new IllegalArgumentException("endpoint must be valid");
        }
        setAttributes(attributes);
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = requireServiceKeyPart(group, "group");
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = requireServiceKeyPart(version, "version");
    }

    public ServiceEndpoint getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(ServiceEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    public Class<?> getServiceInterface() {
        return serviceInterface;
    }

    public void setServiceInterface(Class<?> serviceInterface) {
        this.serviceInterface = serviceInterface;
    }

    public Map<String, String> getAttributes() {
        return Collections.unmodifiableMap(new HashMap<>(attributes == null ? Map.of() : attributes));
    }

    public void setAttributes(Map<String, String> attributes) {
        if (attributes == null) {
            this.attributes = new HashMap<>();
            return;
        }
        Map<String, String> copy = new HashMap<>();
        attributes.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) {
                throw new IllegalArgumentException("Service metadata attributes must have non-blank keys and values");
            }
            if (key.length() > CourierConstants.MAX_REQUEST_METADATA_STRING_LENGTH
                    || value.length() > CourierConstants.MAX_REQUEST_METADATA_STRING_LENGTH
                    || key.chars().anyMatch(Character::isISOControl)
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Service metadata attribute exceeds maximum length");
            }
            copy.put(key, value);
        });
        this.attributes = copy;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof ServiceMetadata that)) {
            return false;
        }
        return Objects.equals(serviceKey(), that.serviceKey())
                && Objects.equals(endpoint, that.endpoint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serviceKey(), endpoint);
    }

    public ServiceMetadata snapshot() {
        ServiceMetadata snapshot = new ServiceMetadata();
        snapshot.serviceName = serviceName;
        snapshot.group = group;
        snapshot.version = version;
        snapshot.endpoint = endpoint == null ? null : new ServiceEndpoint(endpoint.getHost(), endpoint.getPort());
        snapshot.attributes = new HashMap<>(attributes == null ? Map.of() : attributes);
        return snapshot;
    }

    private static String requireServiceKeyPart(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.indexOf(':') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " must not contain ':' or NUL");
        }
        if (value.length() > CourierConstants.MAX_REQUEST_METADATA_STRING_LENGTH
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid or too long");
        }
        return value;
    }
}
