package yunqi.courier.network.netty;

import org.junit.jupiter.api.Test;
import yunqi.courier.common.config.CourierConfig;
import yunqi.courier.network.netty.codec.PayloadTransformer;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class PayloadTransformerTest {

    @Test
    void shouldApplyCompressionAndEncryptionSymmetrically() {
        CourierConfig config = new CourierConfig();
        config.setCompression("lz4");
        config.setEncryption("aes-gcm");
        config.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[16]));
        config.validate();

        PayloadTransformer transformer = PayloadTransformer.fromConfig(config);
        byte[] input = "payload payload payload payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertArrayEquals(input, transformer.decode(transformer.encode(input)));
    }
}
