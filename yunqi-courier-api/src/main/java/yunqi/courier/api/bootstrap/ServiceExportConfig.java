package yunqi.courier.api.bootstrap;

import yunqi.courier.common.constant.CourierConstants;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ServiceExportConfig<T> {

    private final Class<T> interfaceClass;

    private final T serviceImpl;

    private String group = "default";

    private String version = "1.0.0";

    private final Map<String, String> attributes = new HashMap<>();

    public ServiceExportConfig(Class<T> interfaceClass, T serviceImpl) {
        if (interfaceClass == null || !interfaceClass.isInterface()) {
            throw new IllegalArgumentException("interfaceClass must be an interface");
        }
        if (serviceImpl == null || !interfaceClass.isInstance(serviceImpl)) {
            throw new IllegalArgumentException("serviceImpl must implement interfaceClass");
        }
        this.interfaceClass = interfaceClass;
        this.serviceImpl = serviceImpl;
    }

    public static <T> ServiceExportConfig<T> of(Class<T> interfaceClass, T serviceImpl) {
        return new ServiceExportConfig<>(interfaceClass, serviceImpl);
    }

    public ServiceExportConfig<T> group(String group) {
        this.group = requireText(group, "group");
        return this;
    }

    public ServiceExportConfig<T> version(String version) {
        this.version = requireText(version, "version");
        return this;
    }

    public ServiceExportConfig<T> attribute(String key, String value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("attribute key must not be blank");
        }
        if (key.length() > CourierConstants.MAX_REQUEST_METADATA_STRING_LENGTH
                || key.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("attribute key is invalid or too long");
        }
        if (value == null) {
            attributes.remove(key);
        } else {
            if (value.length() > CourierConstants.MAX_REQUEST_METADATA_STRING_LENGTH
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("attribute value is invalid or too long");
            }
            attributes.put(key, value);
        }
        return this;
    }

    public ServiceExportConfig<T> weight(int weight) {
        if (weight <= 0) {
            throw new IllegalArgumentException("weight must be positive");
        }
        return attribute(CourierConstants.SERVICE_ATTRIBUTE_WEIGHT, Integer.toString(weight));
    }

    public ServiceExportConfig<T> healthy(boolean healthy) {
        return attribute(CourierConstants.SERVICE_ATTRIBUTE_HEALTHY, Boolean.toString(healthy));
    }

    public Class<T> getInterfaceClass() {
        return interfaceClass;
    }

    public T getServiceImpl() {
        return serviceImpl;
    }

    public String getGroup() {
        return group;
    }

    public String getVersion() {
        return version;
    }

    public Map<String, String> getAttributes() {
        return Collections.unmodifiableMap(new HashMap<>(attributes));
    }

    private static String requireText(String value, String field) {
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
