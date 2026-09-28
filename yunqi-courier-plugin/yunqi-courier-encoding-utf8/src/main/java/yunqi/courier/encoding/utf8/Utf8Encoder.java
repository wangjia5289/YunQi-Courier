package yunqi.courier.encoding.utf8;

import yunqi.courier.kernel.spi.core.SPI;
import yunqi.courier.kernel.spi.encoding.Encoder;

import java.nio.charset.StandardCharsets;

@SPI("utf-8")
public final class Utf8Encoder implements Encoder {

    @Override
    public byte[] encode(String text) {
        return text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String decode(byte[] bytes) {
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }
}
