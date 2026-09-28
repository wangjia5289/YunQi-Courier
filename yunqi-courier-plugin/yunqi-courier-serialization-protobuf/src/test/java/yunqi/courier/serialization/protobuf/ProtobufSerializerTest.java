package yunqi.courier.serialization.protobuf;

import com.google.protobuf.StringValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtobufSerializerTest {

    @Test
    void shouldRoundTripGeneratedMessage() {
        ProtobufSerializer serializer = new ProtobufSerializer();
        StringValue original = StringValue.of("protobuf");
        StringValue restored = serializer.deserialize(serializer.serialize(original), StringValue.class);
        assertEquals(original, restored);
    }
}
