package yunqi.courier.serialization.protobuf;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import yunqi.courier.common.exception.SerializeException;
import yunqi.courier.kernel.spi.core.SPI;
import yunqi.courier.kernel.spi.serialization.Serializer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;

/** Protobuf serializer for generated {@link MessageLite} DTOs. */
@SPI("protobuf")
public final class ProtobufSerializer implements Serializer {

    private final ConcurrentHashMap<Class<?>, Parser<? extends MessageLite>> parsers = new ConcurrentHashMap<>();

    @Override
    public byte code() {
        return 2;
    }

    @Override
    public byte[] serialize(Object object) {
        if (!(object instanceof MessageLite message)) {
            throw new SerializeException("Protobuf payload must implement MessageLite", null);
        }
        return message.toByteArray();
    }

    @Override
    public <T> T deserialize(byte[] bytes, Class<T> targetClass) {
        if (bytes == null || targetClass == null || !MessageLite.class.isAssignableFrom(targetClass)) {
            throw new SerializeException("Protobuf target must implement MessageLite", null);
        }
        try {
            MessageLite value = parsers.computeIfAbsent(targetClass, ProtobufSerializer::parserFor)
                    .parseFrom(bytes);
            return targetClass.cast(value);
        } catch (InvalidProtocolBufferException | RuntimeException e) {
            if (e instanceof SerializeException serializeException) {
                throw serializeException;
            }
            throw new SerializeException("Protobuf deserialize failed, targetClass=" + targetClass.getName(), e);
        }
    }

    private static Parser<? extends MessageLite> parserFor(Class<?> targetClass) {
        try {
            Method method = targetClass.getDeclaredMethod("getDefaultInstance");
            if (!Modifier.isStatic(method.getModifiers())) {
                throw new IllegalArgumentException("getDefaultInstance must be static");
            }
            if (!method.canAccess(null)) {
                method.trySetAccessible();
            }
            Object value = method.invoke(null);
            if (!(value instanceof MessageLite message)) {
                throw new IllegalArgumentException("getDefaultInstance did not return MessageLite");
            }
            return message.getParserForType();
        } catch (InvocationTargetException e) {
            throw new SerializeException("Create protobuf parser failed", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new SerializeException("Create protobuf parser failed, targetClass=" + targetClass.getName(), e);
        }
    }
}
