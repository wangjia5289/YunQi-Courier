package yunqi.courier.encoding.utf8;

import org.junit.jupiter.api.Test;
import yunqi.courier.kernel.spi.core.ServiceProviderLoader;
import yunqi.courier.kernel.spi.encoding.Encoder;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Utf8EncoderTest {

    @Test
    void shouldLoadAndRoundTripUnicode() {
        Encoder encoder = ServiceProviderLoader.newInstance(Encoder.class, "utf-8");
        assertEquals("云栖-Courier", encoder.decode(encoder.encode("云栖-Courier")));
    }
}
