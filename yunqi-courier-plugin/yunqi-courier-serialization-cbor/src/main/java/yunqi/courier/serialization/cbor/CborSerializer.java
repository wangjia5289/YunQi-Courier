package yunqi.courier.serialization.cbor;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import yunqi.courier.common.exception.SerializeException;
import yunqi.courier.kernel.spi.core.SPI;
import yunqi.courier.kernel.spi.serialization.Serializer;

/** CBOR serializer for compact binary RPC payloads while retaining JSON's type policy. */
@SPI("cbor")
public final class CborSerializer implements Serializer {

    private final ObjectMapper objectMapper;

    public CborSerializer() {
        BasicPolymorphicTypeValidator validator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("java.lang.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.time.")
                .allowIfSubType("yunqi.courier.")
                .allowIfSubType(new BasicPolymorphicTypeValidator.TypeMatcher() {
                    @Override
                    public boolean match(MapperConfig<?> config, Class<?> rawType) {
                        if (!rawType.isArray()) {
                            return false;
                        }
                        Class<?> component = rawType.getComponentType();
                        return component.isPrimitive() || component.getName().startsWith("java.")
                                || component.getName().startsWith("yunqi.courier.");
                    }
                })
                .build();
        CBORFactory factory = CBORFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(100)
                        .maxDocumentLength(16L * 1024 * 1024)
                        .maxNumberLength(10_000)
                        .maxStringLength(1_000_000)
                        .maxNameLength(4_096)
                        .build())
                .build();
        objectMapper = new ObjectMapper(factory);
        objectMapper.setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE);
        objectMapper.setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);
        objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.activateDefaultTyping(validator, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
    }

    @Override
    public byte code() {
        return 3;
    }

    @Override
    public byte[] serialize(Object object) {
        try {
            return objectMapper.writeValueAsBytes(object);
        } catch (Exception e) {
            throw new SerializeException("CBOR serialize failed", e);
        }
    }

    @Override
    public <T> T deserialize(byte[] bytes, Class<T> targetClass) {
        try {
            return objectMapper.readValue(bytes, targetClass);
        } catch (Exception e) {
            throw new SerializeException("CBOR deserialize failed, targetClass=" + targetClass.getName(), e);
        }
    }
}
