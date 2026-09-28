package yunqi.courier.binary2text.base64url;

import yunqi.courier.kernel.spi.binary2text.BinaryToTextCodec;
import yunqi.courier.kernel.spi.core.SPI;

import java.util.Base64;

@SPI("base64url")
public final class Base64UrlBinaryToTextCodec implements BinaryToTextCodec {

    @Override
    public String encode(byte[] bytes) {
        return bytes == null || bytes.length == 0 ? ""
                : Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public byte[] decode(String text) {
        return text == null || text.isEmpty() ? new byte[0] : Base64.getUrlDecoder().decode(text);
    }
}
