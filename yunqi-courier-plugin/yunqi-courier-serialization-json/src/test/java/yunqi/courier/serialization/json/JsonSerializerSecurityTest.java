package yunqi.courier.serialization.json;

import yunqi.courier.common.exception.SerializeException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonSerializerSecurityTest {

    @Test
    void shouldRejectTypesOutsideTheAllowlist() {
        JsonSerializer serializer = new JsonSerializer();
        byte[] payload = "[\"com.example.NotAllowed\",{}]".getBytes(StandardCharsets.UTF_8);

        assertThrows(SerializeException.class, () -> serializer.deserialize(payload, Object.class));
    }

    @Test
    void shouldRejectDisallowedArrayComponentTypes() {
        JsonSerializer serializer = new JsonSerializer();
        byte[] payload = "[\"[Ljava.net.URI;\",[\"file:/tmp/probe\"]]"
                .getBytes(StandardCharsets.UTF_8);

        assertThrows(SerializeException.class, () -> serializer.deserialize(payload, Object.class));
    }
}
