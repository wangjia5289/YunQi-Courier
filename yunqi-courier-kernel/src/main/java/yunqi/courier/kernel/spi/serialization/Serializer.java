package yunqi.courier.kernel.spi.serialization;

public interface Serializer {

    byte code();

    byte[] serialize(Object object);

    <T> T deserialize(byte[] bytes, Class<T> targetClass);
}
