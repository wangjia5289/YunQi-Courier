package yunqi.courier.serialization.cbor;

import org.junit.jupiter.api.Test;
import yunqi.courier.common.protocol.CourierResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CborSerializerTest {

    @Test
    void shouldRoundTripRpcResponse() {
        CborSerializer serializer = new CborSerializer();
        CourierResponse original = CourierResponse.success("1", "cbor");
        CourierResponse restored = serializer.deserialize(serializer.serialize(original), CourierResponse.class);
        assertEquals(original.getRequestId(), restored.getRequestId());
        assertEquals(original.getData(), restored.getData());
    }
}
