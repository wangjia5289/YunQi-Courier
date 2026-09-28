package yunqi.courier.serialization.json;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import yunqi.courier.common.exception.SerializeException;
import yunqi.courier.kernel.spi.core.SPI;
import yunqi.courier.kernel.spi.serialization.Serializer;

@SPI("json")
public class JsonSerializer implements Serializer {

    private final ObjectMapper objectMapper;

    public JsonSerializer() {
        BasicPolymorphicTypeValidator validator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("java.lang.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.time.")
                .allowIfSubType("yunqi.courier.")
                // Arrays are checked recursively. Jackson's built-in
                // allowIfSubTypeIsArray() trusts the component type and would
                // let a disallowed type such as java.net.URI[] through.
                .allowIfSubType(new BasicPolymorphicTypeValidator.TypeMatcher() {
                    @Override
                    public boolean match(com.fasterxml.jackson.databind.cfg.MapperConfig<?> config,
                                         Class<?> rawType) {
                        return isAllowedArray(rawType);
                    }
                })
                .build();
        JsonFactory jsonFactory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(100)
                        .maxDocumentLength(16L * 1024 * 1024)
                        .maxNumberLength(10_000)
                        .maxStringLength(1_000_000)
                        .maxNameLength(4_096)
                        .build())
                .build();
        this.objectMapper = new ObjectMapper(jsonFactory);
        // Serialize the protocol DTO state, not derived convenience methods
        // such as CourierResponse#getResponseCode. Keeping derived properties
        // off the wire also makes rolling upgrades tolerant of new helpers.
        this.objectMapper.setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE);
        this.objectMapper.setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);
        this.objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        // Protocol DTOs gain optional fields over time (for example traceId and
        // authenticationToken); rolling peers must ignore fields they do not yet
        // understand while still validating all required fields at the RPC layer.
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.objectMapper.activateDefaultTyping(validator, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
    }

    @Override
    public byte code() {
        return 1;
    }

    @Override
    public byte[] serialize(Object object) {
        try {
            return objectMapper.writeValueAsBytes(object);
        } catch (Exception e) {
            throw new SerializeException("Json serialize failed", e);
        }
    }

    @Override
    public <T> T deserialize(byte[] bytes, Class<T> targetClass) {
        try {
            return objectMapper.readValue(bytes, targetClass);
        } catch (Exception e) {
            throw new SerializeException("Json deserialize failed, targetClass=" + targetClass.getName(), e);
        }
    }

    private static boolean isAllowedArray(Class<?> rawType) {
        if (!rawType.isArray()) {
            return false;
        }
        Class<?> componentType = rawType.getComponentType();
        if (componentType.isPrimitive()) {
            return true;
        }
        String name = componentType.getName();
        return name.startsWith("java.lang.")
                || name.startsWith("java.util.")
                || name.startsWith("java.time.")
                || name.startsWith("yunqi.courier.")
                || (componentType.isArray() && isAllowedArray(componentType));
    }
}
